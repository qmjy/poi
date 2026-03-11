/*
 * @(#) Assistant.java 	 version 2.0  10/12/2019
 *
 * Copyright (C) 2013-2019 Information Management Systems Institute, Athena R.C., Greece.
 *
 * This library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.github.qmjy.poi.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qmjy.poi.expression.Expr;
import io.github.qmjy.poi.expression.ExprResolver;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Assistance class with various helper methods used in transformation or reverse transformation.
 *
 * @author Kostas Patroumpas
 * @version 2.0
 */


public class Assistant {

    public WKTReader wktReader = null;             //Parses a geometry in Well-Known Text format to a Geometry representation.

    private static Geometry extent = null;            //User-specified polygon to filter out input geometries outside of its extent
    private static Expr logicalFilter = null;        //User-specified conditions (logical expressions) over thematic attributes
    private static Configuration currentConfig;

    private static final Set<String> ISO_LANGUAGES = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));   //List of ISO 639-1 language codes

    private final AtomicLong numberGenerator = new AtomicLong(1L);    //Used to generate serial numbers, i.e., consecutive positive integers starting from 1


    private final SimpleDateFormat gmtDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");         //Used in time notifications


    /**
     * Constructor of the class without explicit declaration of configuration settings.
     */
    public Assistant() {

    }

    /**
     * Constructor of the class with explicit declaration of configuration settings.
     */
    public Assistant(Configuration config) {

        currentConfig = config;

        //Specify time zone in time notifications
        gmtDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        //Decimal format to be used in converting numbers to dates
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        DecimalFormat decDateFormatter = (DecimalFormat) nf;
        decDateFormatter.applyPattern("#.000");


        //Specify a spatial filter
        if (currentConfig.spatialExtent != null) {
            GeometryFactory geometryFactory = JTSFactoryFinder.getGeometryFactory(null);
            WKTReader reader = new WKTReader(geometryFactory);
            try {
                extent = reader.read(currentConfig.spatialExtent);       //E.g. a bounding box for Austria: "POLYGON((9.530749 46.3724535, 17.1607975 46.3724535, 17.1607975 49.0205255, 9.530749 49.0205255, 9.530749 46.3724535))");
            } catch (ParseException e) {
                ExceptionHandler.abort(e, "Spatial extent of filter specification is not a valid WKT geometry. Please check your configuration settings.");
            }
        }

        //Specify a thematic filter for most geographical file formats (case for SHAPEFILE or GeoJSON is handled using native GeoTools filters)
        if ((currentConfig.filterSQLCondition != null) && (!currentConfig.inputFormat.trim().contains("DBMS")) && (!currentConfig.inputFormat.trim().contains("SHAPEFILE")) && (!currentConfig.inputFormat.trim().contains("GEOJSON"))) {
            //Prepare a logical expression for evaluation over the thematic attributes (if specified by user)
            logicalFilter = getLogicalExpression(currentConfig.filterSQLCondition);
        }
    }


    /**
     * Determines the serialization mode in the output RDF triples. Applicable in GRAPH/STREAM transformation modes.
     *
     * @param serialization A string with the user-specified serialization.
     * @return The RIOT language to be used in the serialization.
     */
    public org.apache.jena.riot.Lang getRDFLang(String serialization) {

        return switch (serialization.toUpperCase()) {
            case "TURTLE", "TTL" -> org.apache.jena.riot.Lang.TURTLE;
            case "N3" -> org.apache.jena.riot.Lang.N3;
            case "TRIG" -> org.apache.jena.riot.Lang.TRIG;
            case "RDF/XML-ABBREV", "RDF/XML" -> org.apache.jena.riot.Lang.RDFXML;
            default -> org.apache.jena.riot.Lang.NTRIPLES;
        };
    }


    /**
     * Examines whether a given string is null or empty (blank).
     *
     * @param text The input string
     * @return True if the parameter is null or empty; otherwise, False.
     */
    public boolean isNullOrEmpty(String text) {
        return text == null || text.isEmpty();
    }


    /**
     * Get the current time (in the GMT time zone) to be used in user notifications.
     *
     * @return A timestamp value as string.
     */
    public String getGMTime() {

        //Current Date Time in GMT
        return gmtDateFormat.format(new java.util.Date()) + " GMT";
    }

    /**
     * Notifies the user about progress in parsing input entities.
     *
     * @param numRec The number of entities processed so far.
     */
    public void notifyProgress(int numRec) {

        if (numRec % 1000 == 0)
            System.out.print(this.getGMTime() + " " + Thread.currentThread().getName() + " Processed " + numRec + " entities..." + "\r");
    }


    /**
     * Report statistics upon termination of the transformation process.
     *
     * @param dt              The clock time (in milliseconds) elapsed since the start of transformation process.
     * @param numRec          The total number of input entities that have been processed.
     * @param rejectedRec     The total number of input entities that were rejected during processing.
     * @param numTriples      The number of RDF triples resulted from transformation.
     * @param serialization   A string with the user-specified serialization of output triples.
     * @param attrStatistics  Statistics collected per attribute during transformation.
     * @param mbr             The MBR of transformed geometries (in WGS1984 georeference).
     * @param mode            Transformation mode, as specified in the configuration.
     * @param targetSRID      Output spatial reference system (CRS).
     * @param outputFile      Path to the output file containing the RDF triples.
     * @param partition_index The identifier (index) of the partition in case of Spark execution.
     */
    public void reportStatistics(long dt, int numRec, int rejectedRec, int numTriples, String serialization, Map<String, Integer> attrStatistics, Envelope mbr, String mode, String targetSRID, String outputFile, int partition_index) {

        String msg = "";
        if (currentConfig.runtime.equalsIgnoreCase("JVM")) {
            msg += this.getGMTime() + " Thread " + Thread.currentThread().getName() + " completed successfully in " + dt + " ms. " + (numRec - rejectedRec) + " entities transformed into " + numTriples + " triples and exported to " + serialization + " file: " + outputFile + ".";
        } else if (currentConfig.runtime.equalsIgnoreCase("SPARK")) {
            msg += this.getGMTime() + " Worker " + partition_index + " completed successfully in " + dt + " ms. " + (numRec - rejectedRec) + " entities transformed into " + numTriples + " triples and exported to " + serialization + " file: " + outputFile + ".";
        }

        if (rejectedRec > 0)
            msg += " " + rejectedRec + " input entities were excluded from transformation due to specified filters or invalid geometries.";

        msg += " " + printMBR(mbr);   //Include information on spatial extent (if available).
        System.out.println(msg);

        //Metadata regarding execution of this process
        Map<String, Object> execStatistics = new HashMap<>();
        execStatistics.put("Execution time (ms)", dt);
        execStatistics.put("Input entities parsed", numRec);
        execStatistics.put("Input entities transformed", numRec - rejectedRec);
        execStatistics.put("Input entities excluded", rejectedRec);
        execStatistics.put("Output triple count", numTriples);
        execStatistics.put("Output serialization", serialization);
        execStatistics.put("Output CRS", targetSRID != null ? targetSRID : "EPSG:4326");            //SRID as specified in user's configuration
        //Assuming default CRS: WGS84
        execStatistics.put("Output file", outputFile);
        execStatistics.put("Transformation mode", mode);

        //MBR of transformed geometries
        Map<String, Object> mapMBR = new HashMap<>();
        if (mbr != null) {
            mapMBR.put("X_min", mbr.getMinX());
            mapMBR.put("Y_min", mbr.getMinY());
            mapMBR.put("X_max", mbr.getMaxX());
            mapMBR.put("Y_max", mbr.getMaxY());
        }

        //Compile all metadata together
        Map<String, Object> allStats = new HashMap<>();
        allStats.put("Execution Metadata", execStatistics);
        allStats.put("MBR of transformed geometries (WGS84)", mapMBR);
        allStats.put("Attribute Statistics", new TreeMap<>(attrStatistics));     //Sort collection by attribute name

        //Convert metadata to JSON and write to a file
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(new File(FilenameUtils.removeExtension(outputFile) + "_metadata.json"), allStats);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Removes a given directory and all its contents. Used for removing intermediate files created during transformation.
     *
     * @param path The path to the directory.
     * @return The path to a temporary directory that holds intermediate files.
     */
    public String removeDirectory(String path) {
        File f = new File(path);

        if (f.isDirectory()) {
            if (f.exists()) {
                String[] myfiles = f.list();
                assert myfiles != null;
                for (String myfile : myfiles) {
                    File auxFile = new File(path + "/" + myfile);     //Always safe to use '/' instead of File.separatorChar in any OS
                    if (auxFile.isDirectory())      //Recursively delete files in subdirectory
                    {
                        removeDirectory(auxFile.getPath());
                    }
                    FileUtils.deleteQuietly(auxFile);
                }
                FileUtils.deleteQuietly(f);
            }
        }

        return path;
    }

    /**
     * Removes all files from a given directory. Used for removing intermediate files created during transformation.
     *
     * @param path The path to the directory.
     */
    public void cleanupFilesInDir(String path) {

        File f = new File(path);

        if (f.isDirectory()) {
            if (f.exists()) {
                String[] myfiles = f.list();
                assert myfiles != null;
                for (String myfile : myfiles) {
                    File auxFile = new File(path + "/" + myfile);     //Always safe to use '/' instead of File.separatorChar in any OS
                    if (auxFile.isDirectory())      //Recursively delete files in subdirectory
                    {
                        removeDirectory(auxFile.getPath());
                    }
                    FileUtils.deleteQuietly(auxFile);
                }
            }
        }
    }

    /**
     * Creates a temporary directory under the specified path. Used for holding intermediate files created during transformation.
     *
     * @param path The path to the directory.
     * @return The path to a temporary directory that holds intermediate files.
     */
    public String createDirectory(String path) {

        String dir = path;

        if ((path.charAt(path.length() - 1) != File.separatorChar) && (path.charAt(path.length() - 1) != '/'))
            dir = path + "/";         //Always safe to use '/' instead of File.separatorChar in any OS

        File f = new File(dir);

        if (!f.isDirectory()) {
            f.mkdir();
        }

        dir = dir + UUID.randomUUID() + "/";    //Generate a subdirectory that will be used for thread-safe graph storage when multiple threads are employed

        File fl = new File(dir);
        fl.mkdir();

        return dir;
    }


    /**
     * Prints the MBR of the transformed geometries.
     *
     * @return A string with the coordinates of the two anti-diagonal points defining the MBR.
     */
    private String printMBR(Envelope mbr) {
        if ((mbr == null) || (mbr.isNull()))
            return "";
        return "MBR of transformed geometries: X_min=" + mbr.getMinX() + ", Y_min=" + mbr.getMinY() + ", X_max=" + mbr.getMaxX() + ", Y_max=" + mbr.getMaxY();
    }

    /**
     * Transforms a WKT representation of a geometry into another CRS (reprojection).
     *
     * @param wkt       Input geometry in Well-Known Text.
     * @param transform Parameters for the transformation, including source and target CRS.
     * @return WKT of the transformed geometry.
     */
    public String wktTransform(String wkt, MathTransform transform) {

        Geometry o = null;
        //Apply transformation
        try {
            o = wktReader.read(wkt);
            o = JTS.transform(o, transform);

            //Update the MBR of all geometries processed so far
            //updateMBR(o);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return o.toText();     //Get transformed WKT representation	
    }


    /**
     * Reprojects a given geometry into the WGS84 (lon/lat) coordinate reference system
     *
     * @param wkt  Well-Known Text of the input geometry
     * @param srid EPSG code of the coordinate reference system (CRS) of the geometry
     * @return Geometry reprojected into WG84 system
     */
    public Geometry geomTransformWGS84(String wkt, int srid) {

        WKTReader wktReader = new WKTReader();
        Geometry g = null;
        try {
            g = wktReader.read(wkt);
            if (srid != 4326)                   //In case that geometry is NOT georeferenced in WGS84, ...
            {                                   //... it should be transformed in order to calculate its lon/lat coordinates
                CoordinateReferenceSystem origCRS = CRS.decode("EPSG:" + srid);    //The CRS system of the original geometry
                CoordinateReferenceSystem finalCRS = CRS.decode("EPSG:4326");      //CRS for WGS84

                //Define a MathTransform object and apply it
                MathTransform transform = CRS.findMathTransform(origCRS, finalCRS);
                g = JTS.transform(g, transform);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return g;
    }


    /**
     * Returns the internal geometry representation according to its Well-Known Text serialization.
     *
     * @param wkt WKT of the geometry
     * @return A geometry object
     */
    public Geometry WKT2Geometry(String wkt) {

        WKTReader wktReader = new WKTReader();
        Geometry g = null;
        try {
            g = wktReader.read(wkt);

            //Update the MBR of all geometries processed so far
            //updateMBR(g);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return g;     //Return geometry
    }


    /**
     * Built-in function that returns a pair of lon/lat coordinates (in WGS84) of a geometry as calculated from it is the Well-Known Text representation.
     *
     * @param wkt  WKT of the geometry
     * @param srid the EPSG code of the CRS of this geometry
     * @return An array with the pair of lon/lat coordinates
     */
    public double[] getLonLatCoords(String wkt, int srid) {

        Geometry g = geomTransformWGS84(wkt, srid);
        if (g != null) {
            //Update the MBR of all geometries processed so far
            //updateMBR(g);

            //Calculate the coordinates of its centroid
            return new double[]{g.getCentroid().getX(), g.getCentroid().getY()};
        }
        return null;
    }


    /**
     * Calculate the longitude at the centroid of the geometry
     *
     * @param g Input geometry
     * @return Longitude (in WGS84) of the centroid of the geometry
     */
    public double getLongitude(Geometry g) {

        return g.getCentroid().getX();
    }

    /**
     * Calculate the latitude at the centroid of the geometry
     *
     * @param g Input geometry
     * @return Latitude (in WGS84) of the centroid of the geometry
     */
    public double getLatitude(Geometry g) {

        return g.getCentroid().getY();
    }


    /**
     * Specify a topological filter (CONTAINS) against input geometries in order to exclude transformation of those outside a user-specified spatial extent.
     *
     * @param wkt Input geometry as WKT
     * @return True if geometry qualifies; False if geometry should be excluded from transformation.
     */
    public boolean filterContains(String wkt) {
        //Apply topological filter and skip transformation of non-qualifying objects
        //Polygonal extent must have been specified as a valid WKT in user configuration
        return (extent == null) || (extent.contains(this.WKT2Geometry(wkt)));
    }


    /**
     * Evaluates a thematic filter (logical expression) over an input data record.
     *
     * @param record Input data record (including all thematic attributes available in the original feature)
     * @return False if data record qualifies; True if record should be excluded from transformation.
     */
    public boolean filterThematic(Map<String, String> record) {
        if (logicalFilter != null)
            return !logicalFilter.evaluate(record);
        return false;                  //No filter specified, so this record should not be excluded from transformation
    }


    /**
     * Provides the next serial number (long) to be used as an intermediate identifier
     *
     * @return A long number.
     */
    public long getNextSerial() {
        return numberGenerator.getAndIncrement();
    }

    /**
     * Built-in function that provides a UUID (Universally Unique Identifier) that represents a 128-bit long value to be used in the URI of a transformed feature.
     * Also known as GUID (Globally Unique Identifier).
     *
     * @param featureSource The name of the feature source, to be used as suffix of the identifier.
     * @param id            A unique identifier of the feature.
     * @return The auto-generated UUID based on the concatenation of the feature source and the identifier.
     */
    public String getUUID(String featureSource, String id) {

        UUID uuid = null;

        //Auto-generate a serial number in case that no unique identifier is available for the original feature
        //CAUTION! This serial number is neither retained not emitted in the resulting triples
        if (id == null)
            id = Long.toString(getNextSerial());

        //UUIDs generated by hashing over the concatenation of feature source name and the identifier
        try {
            byte[] bytes = (featureSource + id).getBytes(StandardCharsets.UTF_8);
            uuid = UUID.nameUUIDFromBytes(bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return uuid.toString();
    }

    /**
     * Built-in function that provides a UUID (Universally Unique Identifier) that represents a 128-bit long value to be used in the URI of a transformed feature.
     * This UUID is generated by hashing over the original identifier. Also known as GUID (Globally Unique Identifier).
     *
     * @param id A unique identifier of the feature.
     * @return The auto-generated UUID.
     */
    public String getUUID(String id) {

        UUID uuid = null;

        try {
            byte[] bytes = id.getBytes(StandardCharsets.UTF_8);     //UUIDs generated by hashing over the original identifier
            uuid = UUID.nameUUIDFromBytes(bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return uuid.toString();
    }

    /**
     * Built-in function that provides a UUID (Universally Unique Identifier) that represents a 128-bit long value to be used in the URI of a transformed feature.
     * This is a secure random UUID with minimal chance of collisions with existing ones. Also known as GUID (Globally Unique Identifier).
     *
     * @return The auto-generated UUID.
     */
    public String getRandomUUID() {
        return UUID.randomUUID().toString();          //random UUID
    }


    /**
     * Checks if the given string represents a valid ISO 639-1 language code (2 digits)
     *
     * @param s An input string value
     * @return True is this is a valid ISO 639-1 language code; otherwise, False.
     */
    public boolean isValidISOLanguage(String s) {
        return ISO_LANGUAGES.contains(s);
    }


    /**
     * Returns a logical expression to be evaluated against a record (represented as a Map with key-value pairs); evaluation returns a boolean value.
     *
     * @param condition An SQL condition involving equality, possibly combining several subexpressions with AND, OR
     * @return A logical expression to be evaluated
     */
    public Expr getLogicalExpression(String condition) {

        Expr expr = null;     //Currently supporting =, <>, <, <=, >, >= against string and numeric literals; subexpressions can be combined with AND, OR operators; NOT is not supported
        try {
            ExprResolver exprResolver = new ExprResolver();
            expr = exprResolver.parse(exprResolver.tokenize(condition));   //Example condition: "type = 'BUSSTOP' AND (name = 'Majelden' OR name = 'Kaserngatan')"
        } catch (java.text.ParseException e) {
            ExceptionHandler.abort(e, "Logical expression specified for thematic filtering is not valid. Please check your configuration settings.");
        }

        return expr;
    }

    /**
     * Applies a function at runtime, based on user-defined YML specifications regarding an attribute.
     * This is carried out thanks to the Java Reflection API.
     *
     * @param methodName The name of the method to invoke (e.g., getLanguage).
     * @param args       The necessary arguments for the method to run (maybe multiple).
     * @return A string value resulting from the invocation (e.g., language tag extracted from the attribute name).
     */
    public Object applyRuntimeMethod(String methodName, Object[] args) {
        Method method;
        Object res = null;
        try {
            Class<?>[] params = new Class[args.length];
            //Check each argument and determine its class or type
            for (int i = 0; i < args.length; i++) {
                //Handle data types in case they are needed in built-in functions
                if (args[i] instanceof String)                 //String
                    params[i] = String.class;
                else if (args[i] instanceof Integer)           //Integer
                    params[i] = Integer.TYPE;
                else if (args[i] instanceof Double)            //Double
                    params[i] = Double.TYPE;
                else if (args[i] instanceof Float)             //Float
                    params[i] = Float.TYPE;
                else if (args[i] instanceof Date)              //Date
                    params[i] = Date.class;
                else if (args[i] instanceof Timestamp)         //Timestamp
                    params[i] = Timestamp.class;
                else if (args[i] instanceof Geometry)          //Geometry
                    params[i] = Geometry.class;
            }
            //Identify the method that should be invoked with its proper parameters
            method = this.getClass().getDeclaredMethod(methodName, params);
            res = method.invoke(this, args);        //Result of the method should be cast to a data type by the caller
        } catch (SecurityException | NoSuchMethodException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            e.printStackTrace();
        }

        return res;
    }

}
