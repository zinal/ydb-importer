package ydb.importer;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import tech.ydb.table.description.TableColumn;
import tech.ydb.table.description.TableDescription;
import tech.ydb.table.values.StructType;
import tech.ydb.table.values.Type;
import ydb.importer.config.*;
import ydb.importer.source.*;
import ydb.importer.target.*;
import static ydb.importer.config.JdomHelper.*;

/**
 *
 * @author zinal
 */
public class YdbImporter {

    private final static org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(YdbImporter.class);
    
    public static final String VERSION = "1.0";

    private final ImporterConfig config;
    private final TableMapList tableMaps;
    private SourceCP sourceCP = null;
    private TargetCP targetCP = null;
    private AnyTableLister tableLister = null;

    public YdbImporter(ImporterConfig config) {
        this.config = config;
        this.tableMaps = new TableMapList(config);
    }

    public ImporterConfig getConfig() {
        return config;
    }

    public TableMapList getTableMaps() {
        return tableMaps;
    }

    public SourceCP getSourceCP() {
        return sourceCP;
    }

    public TargetCP getTargetCP() {
        return targetCP;
    }

    public AnyTableLister getTableLister() {
        return tableLister;
    }

    public void run() throws Exception {
        String jdbcClassName = config.getSource().getClassName();
        if ( ! isBlank(jdbcClassName) ) {
            LOG.info("Loading driver class {}", jdbcClassName);
            Class.forName(config.getSource().getClassName());
        }
        LOG.info("Connecting to the source database {}", 
                config.getSource().getJdbcUrl());
        tableLister = AnyTableLister.getInstance(tableMaps);
        sourceCP = new SourceCP(config.getSource(), config.getWorkers().getPoolSize());
        try {
            final List<TableDecision> tables = new ArrayList<>();
            try (Connection con = sourceCP.getConnection()) {
                LOG.info("Retrieving table list...");
                for (TableDecision nd : tableLister.selectTables(con)) {
                    tables.add(nd);
                }
            }
            LOG.info("\ttotal {} tables to be processed", tables.size());
            LOG.info("Starting async workers...");
            final ExecutorService workers = makeWorkers();
            try {
                LOG.info("Retrieving table metadata...");
                retrieveSourceMetadata(tables, workers);
                LOG.info("\ttotal {} tables with metadata", tables.size());
                if (! tables.isEmpty()) {
                    // Save the table creation scripts to file
                    dumpTableScripts(tables);
                    if (config.hasTarget()) {
                        LOG.info("Connecting to the target database {}",
                                config.getTarget().getConnectionString());
                        targetCP = new TargetCP(config.getTarget(), config.getWorkers().getPoolSize());
                        // Drop/Create/Read metadata from target
                        createMissingTables(workers, tables);
                        // Load data if necessary
                        if (config.getTarget().isLoadData())
                            loadTableData(workers, tables);
                    }
                }
                LOG.info("Shutting down workers...");
                workers.shutdown();
            } finally {
                if ( ! workers.isShutdown() ) {
                    List<Runnable> pending = workers.shutdownNow();
                    if (pending!=null && !pending.isEmpty()) {
                        LOG.warn("Workers have been shut down with {} tasks pending", pending.size());
                    }
                }
            }
        } finally {
            if (sourceCP != null) {
                LOG.info("Closing source connection pool...");
                sourceCP.close();
            }
            if (targetCP != null) {
                LOG.info("Closing target connection pool...");
                targetCP.close();
            }
        }
    }

    private ExecutorService makeWorkers() {
        return Executors.newFixedThreadPool(config.getWorkers().getPoolSize(),
                new WorkerFactory(this));
    }

    private void retrieveSourceMetadata(List<TableDecision> tables, ExecutorService workers)
            throws Exception {
        List<Future<MetadataTask.Out>> metadatas = new ArrayList<>(tables.size());
        for (TableDecision td : tables) {
            metadatas.add( workers.submit(new MetadataTask(this, td)) );
        }
        tables.clear();
        for (Future<MetadataTask.Out> outf : metadatas) {
            MetadataTask.Out out = outf.get();
            if (out.isSuccess()) {
                // proceed only with tables which had no failures
                out.td.setMetadata(out.tm);
                new YdbTableBuilder(out.td).build();
                tables.add(out.td);
            } else {
                // Mark the failed table
                out.td.setFailure(true);
            }
        }
    }
    
    private void dumpTableScripts(List<TableDecision> tables) throws Exception {
        if (config.getTarget()==null || config.getTarget().getScript() == null)
            return;
        String fileName = config.getTarget().getScript().getFileName();
        try ( FileWriter fw = new FileWriter(fileName, StandardCharsets.UTF_8);
              BufferedWriter writer = new BufferedWriter(fw) ) {
            for (TableDecision td : tables) {
                if (td.isFailure())
                    continue;
                for (TargetTable blobTable : td.getBlobTargets().values())
                    YdbTableBuilder.appendTo(writer, blobTable);
                YdbTableBuilder.appendTo(writer, td.getTarget());
            }
        }
        LOG.info("YDB DDL saved to {}", fileName);
    }

    private void createMissingTables(ExecutorService es, List<TableDecision> tables)
            throws Exception {
        if (config.getTarget()==null)
            return;
        LOG.info("Target tables creation started.");
        final List<Future<CreateTableTask.Out>> results = new ArrayList<>();
        for (TableDecision td : tables) {
            if (td.isFailure())
                continue;
            for (TargetTable yt : td.getBlobTargets().values()) {
                results.add( es.submit(new CreateTableTask(this, yt)) );
            }
            results.add( es.submit(new CreateTableTask(this, td.getTarget())) );
        }
        int successCount = 0;
        for (Future<CreateTableTask.Out> rf : results) {
            CreateTableTask.Out r = rf.get();
            if (r.success) {
                ++successCount;
                if (r.existingTable != null)
                    adjustTargetStructure(r.table, r.existingTable);
            } else {
                r.table.getOriginal().setFailure(true);
            }
        }
        LOG.info("Target tables creation completed {} of {}.", successCount, results.size());
    }

    private void adjustTargetStructure(TargetTable table, TableDescription desc) {
        final Map<String, Type> fields = new HashMap<>();
        for (TableColumn tc : desc.getColumns()) {
            if (Type.Kind.OPTIONAL.equals(tc.getType().getKind()))
                fields.put(tc.getName(), tc.getType());
            else
                fields.put(tc.getName(), tc.getType().makeOptional());
        }
        table.setFields(StructType.of(fields));
    }

    private void loadTableData(ExecutorService es, List<TableDecision> tables) throws Exception {
        if (config.getTarget()==null)
            return;
        try (ProgressCounter progress = new ProgressCounter()) {
            final List<Future<LoadDataTask.Out>> results = new ArrayList<>();
            for (TableDecision td : tables) {
                if (td.isFailure())
                    continue;
                results.add( es.submit(new LoadDataTask(this, progress, td)) );
            }
            if (results.isEmpty()) {
                LOG.info("No valid tables to be loaded, nothing to do.");
                return;
            }
            int successCount = 0;
            for (Future<LoadDataTask.Out> rf : results) {
                LoadDataTask.Out r = rf.get();
                if (r.success)
                    ++successCount;
                else
                    r.tab.setFailure(true);
            }
            LOG.info("Table data load completed {} of {} tasks.", successCount, results.size());
        }
    }

    public static void main(String[] args) {
        LOG.info("{} version {}", YdbImporter.class.getSimpleName(), VERSION);
        if (args.length != 1) {
            LOG.info("Single argument is expected: config-file.xml");
            System.exit(2);
        }
        try {
            LOG.info("Reading configuration {}...", args[0]);
            final ImporterConfig importerConfig = new ImporterConfig(
                        JdomHelper.readDocument(args[0]) );
            if (! importerConfig.validate()) {
                LOG.error("Configuration is not valid, TERMINATING");
                System.exit(1);
            }
            LOG.info("Running imports...");
            new YdbImporter(importerConfig).run();
            LOG.info("Imports completed successfully!");
        } catch(Exception ex) {
            LOG.error("FATAL", ex);
            System.exit(1);
        }
    }

    public static final class WorkerFactory implements ThreadFactory {

        private final YdbImporter owner;
        private int counter = 0;

        public WorkerFactory(YdbImporter owner) {
            this.owner = owner;
        }

        @Override
        public Thread newThread(Runnable r) {
            final Thread t = new Thread(() -> {
                BlobSaver.initCounter(counter);
                r.run();
            }, "YdbImporter-worker-" + counter);
            t.setDaemon(false);
            ++counter;
            return t;
        }

    }

}
