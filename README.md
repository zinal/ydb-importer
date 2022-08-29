# YDB Data Import Tool

This small utility allows to import tables into YDB (https://ydb.tech) from various JDBC data sources.
Currently PostgreSQL and Oracle Database data sources are supported.
The support for additional data source types is simple and fast to add.

## Running the tool

To get the tool, go to the Releases page, and download the ZIP archive with the binary.

See README.txt inside the ZIP archive for configuration details.


## Running inside Apache NetBeans IDE

1. Open Project Properties >> Actions >> Run Project, Debug Project

2. Set the necessary environment variables to configure authentication,
as described here: https://ydb.tech/en/docs/reference/ydb-sdk/auth#env

For example:

```
Env.YDB_SERVICE_ACCOUNT_KEY_FILE_CREDENTIALS=/Users/mzinal/key-ydb-sa1.json
```

or

```
Env.YDB_SERVICE_ACCOUNT_KEY_FILE_CREDENTIALS=/home/zinal/key-ydb-sa1.json
```
