package io.github.qmjy.poi.utils;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the extracted records directly into an SQLite database file (by convention with a ".poi" suffix).
 * CAUTION! This writer is fed with exactly the same record values that are emitted in the .csv output file,
 * so the schema of the database is derived from the header of that CSV file.
 * The resulting database holds a single table (see {@link Constants#SQLITE_TABLE}) along with a couple of indices
 * on the identifier and the name of each feature, so that records can be readily queried by an offline engine.
 *
 * @author Kostas Patroumpas
 * @version 2.0
 */
public class SqliteWriter implements AutoCloseable {

    /** SLF4J logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteWriter.class);


    /**
     * Number of records committed into the database in a single JDBC batch.
     */
    private static final int BATCH_SIZE = 1000;

    /**
     * Column affinity types employed in the generated table schema.
     */
    private static final int TYPE_TEXT = 0;
    private static final int TYPE_REAL = 1;
    private static final int TYPE_INTEGER = 2;

    private final String dbFile;                          //Path to the SQLite database file that collects the extracted records
    private final String[] columnNames;                   //Names of the columns of the output table
    private final int[] columnTypes;                      //Affinity of each column in the output table

    private Connection connection = null;                 //JDBC connection to the SQLite database file
    private PreparedStatement insertStmt = null;          //Statement used to insert every single record

    private boolean failed = false;                       //Set to true if the database file could not be initialized, so no further attempts are made
    private int pending = 0;                              //Records added to the current batch but not committed yet
    private long numInserted = 0;                         //Total number of records inserted into the database

    /**
     * Constructs a writer for the given SQLite database file, based on the columns of the CSV output file.
     * CAUTION! The database file itself is only created when the first record is actually written into it.
     *
     * @param dbFile  Path to the SQLite database file that will collect the extracted records.
     * @param headers Ordered names of the columns, as these appear in the header of the .csv output file.
     */
    public SqliteWriter(String dbFile, List<String> headers) {

        this.dbFile = dbFile;
        this.columnNames = buildColumnNames(headers);
        this.columnTypes = buildColumnTypes(headers);
    }

    /**
     * Derives the ordered column names of the SQLite table from the header of the CSV output file.
     * CAUTION! This is the same header that is emitted as the first line of the .csv output file.
     *
     * @param mappedColumns Names of the extra columns resulting from the attribute mapping specification.
     * @return The ordered column names for the SQLite table.
     */
    public static List<String> buildHeaders(List<String> mappedColumns) {

        List<String> headers = new ArrayList<>(Arrays.asList(Constants.OUTPUT_CSV_HEADER.split("\\|")));
        if (mappedColumns != null)
            headers.addAll(mappedColumns);
        headers.add(Constants.OTHER_TAGS_COLUMN);

        return headers;
    }

    /**
     * Provides the path to the SQLite database file corresponding to the given output file.
     *
     * @param outputFile Path to the output file that collects the extracted records (typically in .nt or .csv format).
     * @return Path to the SQLite database file, keeping the name of the dataset but with a ".poi" suffix.
     */
    public static String dbFilePath(String outputFile) {
        return FilenameUtils.removeExtension(outputFile) + Constants.SQLITE_EXTENSION;
    }

    /**
     * Converts the fields of a single record as emitted in the .csv file into the values to be stored in the database.
     * CAUTION! In the CSV output, the CATEGORY cell actually carries both the CATEGORY and the SUBCATEGORY values,
     * separated by the delimiter, so that it spans the two respective columns of the CSV header.
     * Since the SQLite table keeps these two values in distinct columns, that cell has to be split accordingly.
     *
     * @param csvFields Values of a single record, exactly as these were written in the .csv file.
     * @return Values matching one-to-one the columns of the CSV header (and hence of the SQLite table).
     */
    public static List<String> fromCsvRecord(List<String> csvFields) {

        if ((csvFields == null) || (csvFields.size() < 3))
            return csvFields;                                //Nothing to split in this unusual case

        List<String> fields = new ArrayList<>(csvFields);
        String category = fields.remove(2);                  //This cell holds CATEGORY and SUBCATEGORY joined with the delimiter
        String[] parts = category.split("\\|", 2);

        fields.add(2, parts[0]);                             //CATEGORY
        fields.add(3, (parts.length > 1) ? parts[1] : "");   //SUBCATEGORY (may be empty)

        return fields;
    }

    /**
     * Determines the SQL data type of each column according to its name in the CSV header.
     */
    private static int[] buildColumnTypes(List<String> headers) {

        int[] types = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            String name = (headers.get(i) == null) ? "" : headers.get(i).trim().toUpperCase(Locale.ROOT);
            types[i] = switch (name) {
                case "LON", "LAT" -> TYPE_REAL;
                case "SRID" -> TYPE_INTEGER;
                default -> TYPE_TEXT;
            };
        }

        return types;
    }

    /**
     * Generates a unique, non-empty name for each column, keeping the original names wherever possible.
     * Any repeated column name gets a numeric suffix so that the generated DDL remains valid.
     */
    private static String[] buildColumnNames(List<String> headers) {

        Map<String, Integer> occurrences = new LinkedHashMap<>();
        String[] names = new String[headers.size()];

        for (int i = 0; i < headers.size(); i++) {
            String raw = headers.get(i);
            String base = ((raw == null) || (raw.trim().isEmpty())) ? "COLUMN_" + (i + 1) : raw.trim();
            int n = occurrences.merge(base.toUpperCase(Locale.ROOT), 1, Integer::sum);
            names[i] = (n == 1) ? base : base + "_" + n;
        }

        return names;
    }

    /**
     * Creates the SQLite database file (or overwrites an existing one) and prepares the table as well as the insert statement.
     * CAUTION! This method is invoked lazily, i.e., only when the first record is about to be written.
     */
    private void init() throws SQLException, ClassNotFoundException, IOException {

        File file = new File(dbFile);
        File parent = file.getParentFile();
        if ((parent != null) && (!parent.exists()))
            parent.mkdirs();

        //CAUTION! The database is always created from scratch, exactly like the .csv file that gets truncated on every execution
        if (file.exists() && (!file.delete()))
            throw new IOException("Cannot overwrite existing file: " + dbFile);

        LOGGER.info("Storing extracted records into SQLite database file: {}", dbFile);

        Class.forName("org.sqlite.JDBC");                     //Explicitly load the SQLite JDBC driver
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);

        //Performance tuning: this database file is a write-once artifact, so full durability guarantees are not required
        //CAUTION! These settings must be applied BEFORE switching off auto-commit, since some of them are not allowed inside a transaction
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=OFF");
            st.execute("PRAGMA synchronous=OFF");
            st.execute("PRAGMA temp_store=MEMORY");
        }

        connection.setAutoCommit(false);

        createTable();

        StringBuilder sql = new StringBuilder("INSERT INTO \"" + Constants.SQLITE_TABLE + "\" VALUES (");
        for (int i = 0; i < columnNames.length; i++)
            sql.append(i == 0 ? "?" : ",?");
        sql.append(')');

        insertStmt = connection.prepareStatement(sql.toString());
    }

    /**
     * Creates the table that will collect all extracted records.
     */
    private void createTable() throws SQLException {

        StringBuilder ddl = new StringBuilder("CREATE TABLE \"" + Constants.SQLITE_TABLE + "\" (");
        for (int i = 0; i < columnNames.length; i++) {
            if (i > 0)
                ddl.append(", ");
            ddl.append('"').append(columnNames[i].replace("\"", "\"\"")).append("\" ").append(sqlType(columnTypes[i]));
        }
        ddl.append(')');

        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS \"" + Constants.SQLITE_TABLE + "\"");
            st.executeUpdate(ddl.toString());
        }
    }

    /**
     * Creates indices on the identifier and the name of the extracted features, in order to speed up lookups.
     * Any failure at this stage is reported as a warning only, since indices are an optimization, not a requirement.
     */
    private void createIndexes() {

        try (Statement st = connection.createStatement()) {
            if (hasColumn("ID"))
                st.executeUpdate("CREATE INDEX IF NOT EXISTS \"idx_" + Constants.SQLITE_TABLE + "_id\" ON \"" + Constants.SQLITE_TABLE + "\"(\"ID\")");
            if (hasColumn("NAME"))
                st.executeUpdate("CREATE INDEX IF NOT EXISTS \"idx_" + Constants.SQLITE_TABLE + "_name\" ON \"" + Constants.SQLITE_TABLE + "\"(\"NAME\")");
        } catch (SQLException e) {
            ExceptionHandler.warn(e, "Could not create indices on the output SQLite database file.");
        }
    }

    /**
     * Examines whether the output table contains a column with the given name.
     */
    private boolean hasColumn(String name) {
        for (String column : columnNames) {
            if (column.equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    /**
     * Provides the SQL data type corresponding to the affinity of a column.
     */
    private static String sqlType(int type) {
        return switch (type) {
            case TYPE_REAL -> "REAL";
            case TYPE_INTEGER -> "INTEGER";
            default -> "TEXT";
        };
    }

    /**
     * Inserts a single extracted record into the SQLite database.
     * CAUTION! The provided values must be ordered exactly as the header given at construction time.
     *
     * @param fields Values of the record, one per column of the output table.
     */
    public void insert(List<String> fields) {

        if (failed)
            return;                                          //No point in retrying once the database file could not be initialized

        try {
            if (insertStmt == null)
                init();                                      //The database file is created upon writing of the very first record

            for (int i = 0; i < columnNames.length; i++) {
                String value = ((fields != null) && (i < fields.size()) && (fields.get(i) != null)) ? fields.get(i) : "";
                switch (columnTypes[i]) {
                    case TYPE_REAL -> {
                        try {
                            insertStmt.setDouble(i + 1, Double.parseDouble(value.trim()));      //Numeric columns are stored as numbers, whenever possible
                        } catch (NumberFormatException nfe) {
                            insertStmt.setString(i + 1, value);
                        }
                    }
                    case TYPE_INTEGER -> {
                        try {
                            insertStmt.setLong(i + 1, Long.parseLong(value.trim()));
                        } catch (NumberFormatException nfe) {
                            insertStmt.setString(i + 1, value);
                        }
                    }
                    default -> insertStmt.setString(i + 1, value);
                }
            }

            insertStmt.addBatch();
            numInserted++;

            if (++pending >= BATCH_SIZE)
                flush();

        } catch (Exception e) {
            failed = true;
            ExceptionHandler.warn(e, "Could not insert a record into the output SQLite database file.");
        }
    }

    /**
     * Commits the records accumulated in the current batch.
     */
    private void flush() throws SQLException {
        insertStmt.executeBatch();
        connection.commit();
        pending = 0;
    }

    /**
     * Finalizes the output database: commits any pending records, creates the indices, and closes the file.
     * In case that no record has been written at all, no database file is created.
     */
    @Override
    public void close() {

        try {
            if (insertStmt != null) {
                if (pending > 0)
                    flush();
                insertStmt.close();
                insertStmt = null;
            }

            if (connection != null) {
                connection.commit();
                createIndexes();
                connection.commit();           //CAUTION! The indices must be committed as well, since auto-commit has been switched off
                connection.close();
                connection = null;
            }
        } catch (Exception e) {
            ExceptionHandler.warn(e, "An error occurred while finalizing the output SQLite database file.");
        }
    }

    /**
     * Provides the total number of records inserted into the database.
     *
     * @return The number of records written to the SQLite database file.
     */
    public long getNumInserted() {
        return numInserted;
    }

    /**
     * Provides the path to the SQLite database file managed by this writer.
     *
     * @return Path to the output SQLite database file.
     */
    public String getDbFile() {
        return dbFile;
    }
}
