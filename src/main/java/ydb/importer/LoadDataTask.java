package ydb.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import tech.ydb.table.values.*;
import java.io.InputStream;
import ydb.importer.source.ColumnInfo;
import ydb.importer.target.*;

/**
 *
 * @author zinal
 */
public class LoadDataTask extends ValueConverter implements Callable<LoadDataTask.Out> {

    private final static org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(LoadDataTask.class);

    private final YdbImporter owner;
    private final YdbUpsertOp ydbOp;
    private final TableDecision tab;
    private final ProgressCounter progress;

    private final int maxBatchRows;
    private final String mainTablePath;

    // Total number of rows transferred.
    private final RowCounter counter;
    // ResultSet column position -> StructType element index
    private final List<ConvInfo> mainIndex = new ArrayList<>();
    // Used to build synthetic keys
    private MessageDigest synthDigest = null;
    private Base64.Encoder base64Encoder = null;
    // Used to save BLOB values
    private BlobSaver blobSaver = null;

    public LoadDataTask(YdbImporter owner, ProgressCounter progress, TableDecision tab) {
        this.owner = owner;
        this.ydbOp = new YdbUpsertOp(owner.getTargetCP().getRetryCtx());
        this.tab = tab;
        this.progress = progress;
        this.maxBatchRows = owner.getConfig().getTarget().getMaxBatchRows();
        this.mainTablePath = owner.getTargetCP().getDatabase() + "/" + tab.getTarget().getFullName();
        this.counter = new RowCounter("failed upsert to " + tab.getTarget().getFullName(), progress);
    }

    @Override
    public Out call() throws Exception {
        if (! tab.isValid()) {
            LOG.warn("Skipping incomplete source table {}.{}", tab.getSchema(), tab.getTable());
            return new Out(tab, false, 0L);
        }
        if (tab.isFailure()) {
            LOG.warn("Skipping failed source table {}.{}", tab.getSchema(), tab.getTable());
            return new Out(tab, false, 0L);
        }
        LOG.info("Loading data from source table {}.{}", tab.getSchema(), tab.getTable());
        try (Connection con = owner.getSourceCP().getConnection();
                PreparedStatement ps = con.prepareStatement(tab.getMetadata().getBasicSql());
                ResultSet rs = ps.executeQuery()) {
            copyData(rs);
            LOG.info("Copied {} rows from source table {}.{}", 
                    counter.getValue(), tab.getSchema(), tab.getTable());
            return new Out(tab, true, counter.getValue());
        } catch(Throwable e) {
            LOG.error("Failed to load data from table {}.{}", tab.getSchema(), tab.getTable(), e);
            return new Out(tab, false, counter.getValue());
        }
    }

    /**
     * Reads the source ResultSet rows and upserts the data to YDB tables.
     * @param rs Input result set
     * @throws Exception 
     */
    private void copyData(ResultSet rs) throws Exception {
        final StructType paramType = tab.getTarget().getFields();
        final ListType paramListType = ListType.of(paramType);
        final ResultSetMetaData rsmd = rs.getMetaData();
        buildMainIndex(paramType, rsmd);

        final List<Value<?>> batch = new ArrayList<>(maxBatchRows);
        while (rs.next()) {
            batch.add( convert(paramType, rs, rsmd) );
            if (batch.size() >= maxBatchRows) {
                ydbOp.start(mainTablePath, paramListType.newValue(batch), counter);
                batch.clear();
            }
        }

        flushBlobSaver();
        if (! batch.isEmpty()) {
            ydbOp.start(mainTablePath, paramListType.newValue(batch), counter);
            batch.clear();
        }
        ydbOp.finish();
    }

    private void buildMainIndex(StructType paramListType, ResultSetMetaData rsmd) throws Exception {
        mainIndex.clear();
        final Map<String, Integer> sourceColumns = new HashMap<>();
        for (int i = 1; i <= rsmd.getColumnCount(); ++i) {
            sourceColumns.put(rsmd.getColumnName(i), i);
        }
        for (int ixTarget = 0; ixTarget < paramListType.getMembersCount(); ixTarget++) {
            String name = paramListType.getMemberName(ixTarget);
            if (TargetTable.SYNTH_KEY_FIELD.equals(name))
                continue;
            ColumnInfo ci = tab.getMetadata().findColumn(name);
            if (ci==null) {
                LOG.warn("Unexpected column {} in the source table {}.{} - SKIPPED", name, 
                        tab.getSchema(), tab.getTable());
                continue;
            }
            Integer ixSource = sourceColumns.get(name);
            if (ixSource == null) {
                LOG.warn("Missing column {} in the source table {}.{} - SKIPPED", name, 
                        tab.getSchema(), tab.getTable());
                continue;
            }
            // Blob checking has to be done based on the ColumnInfo declared type.
            // The reason for this is PostgreSQL ugly approach, where the driver
            // does not return BLOB type even for "lo" typed columns.
            if (ColumnInfo.isBlob(ci.getSqlType())) {
                // We need the full path of the BLOB storage table.
                TargetTable tt = tab.getBlobTargets().get(name);
                if (tt==null) {
                    LOG.warn("Missing aux target table for BLOB column {} "
                            + "of source {}.{}", name, tab.getSchema(), tab.getTable());
                } else {
                    ConvMode cm = ci.isBlobAsObject() ? ConvMode.BLOB_OBJECT : ConvMode.BLOB_STREAM;
                    String blobPath = owner.getTargetCP().getDatabase() + "/" + tt.getFullName();
                    mainIndex.add(new ConvInfo(ixSource, ixTarget, cm, blobPath));
                }
            } else {
                // All but BLOB - generate conversion mode
                ConvMode cm = chooseMode(ixSource, ixTarget, paramListType, rsmd);
                // And put it to the index entry
                mainIndex.add(new ConvInfo(ixSource, ixTarget, cm));
            }
        }
    }

    /**
     * Converts the current row from the source ResultSet to the StructValue representation.
     * @param mainType StructValue type definition
     * @param rs Input result set
     * @return StructValue with the converted copies of fields
     * @throws Exception 
     */
    private StructValue convert(StructType mainType, ResultSet rs, ResultSetMetaData rsmd)
            throws Exception {
        final Value<?> members[] = new Value<?>[mainType.getMembersCount()];
        Arrays.fill(members, VoidValue.of());
        for (ConvInfo ci : mainIndex) {
            try {
                members[ci.targetIndex] = convertValue(rs, ci);
            } catch(Exception ex) {
                throw new Exception("Failed conversion for column " 
                        + mainType.getMemberName(ci.targetIndex), ex);
            }
        }
        if (tab.getTarget().hasSynthKey()) {
            members[tab.getTarget().getSynthKeyPos()] = calcSynthKey(rs, rsmd);
        }
        return mainType.newValueUnsafe(members);
    }
    
    public BlobSaver makeBlobSaver() {
        if (blobSaver==null) {
            blobSaver = new BlobSaver(owner.getConfig().getTarget().getMaxBlobRows(), progress);
        }
        return blobSaver;
    }

    public void flushBlobSaver() {
        if (blobSaver!=null)
            blobSaver.flush(ydbOp);
    }

    @Override
    public PrimitiveValue convertBlob(ResultSet rs, ConvInfo ci) throws Exception {
        try (InputStream is = openStream(rs, ci)) {
            long id = makeBlobSaver().saveBlob(ydbOp, is, ci.blobPath);
            return PrimitiveValue.newInt64(id);
        }
    }

    private InputStream openStream(ResultSet rs, ConvInfo ci) throws Exception {
        if (ConvMode.BLOB_STREAM == ci.mode)
            return rs.getBinaryStream(ci.sourceIndex);
        return rs.getBlob(ci.sourceIndex).getBinaryStream();
    }

    private MessageDigest getSynthDigest() throws Exception {
        if (synthDigest == null)
            synthDigest = MessageDigest.getInstance("SHA-256");
        return synthDigest;
    }
    
    private Base64.Encoder getBase64Encoder() {
        if (base64Encoder == null)
            base64Encoder = Base64.getUrlEncoder().withoutPadding();
        return base64Encoder;
    }

    /**
     * Calculates the synthetic key as a hash over the non-BLOB column values
     * @param rs Input result set positioned to the current row
     * @param rsmd Input result set metadata
     * @return Base64-encoded hash value as a 'String' YDB data type
     * @throws Exception
     */
    private PrimitiveValue calcSynthKey(ResultSet rs, ResultSetMetaData rsmd) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (int i=1; i<=rsmd.getColumnCount(); ++i) {
            if (! ColumnInfo.isBlob(rsmd.getColumnType(i))) {
                String v = rs.getString(i);
                if (v!=null)
                    sb.append(v);
                sb.append(Character.toChars(2));
            }
        }
        byte[] v = getSynthDigest().digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        String base64v = getBase64Encoder().encodeToString(v);
        return PrimitiveValue.newBytes(base64v.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Output of each LoadDataTask
     */
    public static final class Out {

        public final TableDecision tab;
        public final boolean success;
        public final long rowCount;

        public Out(TableDecision tab, boolean success, long rowCount) {
            this.tab = tab;
            this.success = success;
            this.rowCount = rowCount;
        }
    }
    
}
