/*
 * @(#) OsmRdfCsvStreamConverter.java  version 2.0   5/12/2019
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

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.apache.commons.io.FilenameUtils;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.qmjy.poi.osm.OSMRecord;
import org.geotools.api.referencing.operation.MathTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Provides a set of a streaming RDF triples in memory that can be readily serialized into a file.
 * CAUTION! Only working on OSM (XML/PBF) data files. DO NOT USE this version for transformation with other file formats.
 * Note: This version also emits a .CSV output file that includes all tags identified in OSM (XML or PBF) files.
 *
 * @author Kostas Patroumpas
 * @version 2.0
 */
public class OsmRdfCsvStreamConverter implements Converter {

    /** SLF4J logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(OsmRdfCsvStreamConverter.class);


    private static Configuration currentConfig;

    private final List<Triple> results = new ArrayList<>();       //A collection of generated triples

    private final TripleGenerator myGenerator;                    //Generator of triples
    private final Assistant myAssistant;                            //Performs auxiliary operations (geometry transformations, auto-generation of UUIDs, etc.)

    //Used in performance metrics
    private final long t_start;
    private long dt;
    private int numRec;            //Number of entities (records) in input dataset
    private int rejectedRec;       //Number of rejected entities (records) from input dataset after filtering
    private int numTriples;

    private BufferedWriter csvWriter = null;
    private SqliteWriter sqliteWriter = null;      //CAUTION! Optional writer that stores the very same records directly into an SQLite database file (*.poi)
    private final StreamRDF stream;

    ObjectMapper mapperObj;

    //Attribute Map
    private final HashMap<String, String> tagMap = new HashMap<>();
    private final ArrayList<String> cols = new ArrayList<String>();

    public void ReadAttrMappingFile(Configuration config) {

        Properties properties = new Properties();
        try {
            //Read Mapping Config File; CAUTION! Its location is designated in the embedded configuration (see application.properties)
            try (InputStream in = config.openMappingFile()) {
                properties.load(in);
            }

            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                cols.add(entry.getKey().toString());
                String[] value_parts = entry.getValue().toString().replace("\"", "").trim().split(",");

                for (String valuePart : value_parts) {
                    tagMap.put(valuePart.trim(), entry.getKey().toString());
                }
            }
        } catch (IOException io) {
            LOGGER.error("Problems loading configuration file.", io);
        }
    }

    /*
     * Formats a String escaping some special characters like: \n, \r, |
     * */
    public String formatString(String str) {
        return str.replaceAll("\\r\\n|\\r|\\n", " ").replace("|", ";").trim();
    }

    /**
     * Constructs a StreamConverter object that will conduct transformation at STREAM mode.
     *
     * @param config     User-specified configuration for the transformation process.
     * @param assist     Assistant to perform auxiliary operations.
     * @param outputFile Output file that will collect resulting triples.
     */
    public OsmRdfCsvStreamConverter(Configuration config, Assistant assist, String outputFile) {

        super();

        currentConfig = config;       //Configuration parameters as set up by the various conversion utilities (CSV, SHP, DB, etc.)

        myAssistant = assist;
        myGenerator = new TripleGenerator(config, assist);     //Will be used to generate all triples per input feature (record)

        mapperObj = new ObjectMapper();   //CAUTION! Only used in issuing tags for .CSV output

        //Read Mapping File and build Collection.
        ReadAttrMappingFile(currentConfig);

        //Initialize performance metrics
        t_start = System.currentTimeMillis();
        dt = 0;
        numRec = 0;
        rejectedRec = 0;
        numTriples = 0;

        OutputStream outFile = null;
        try {
            outFile = new FileOutputStream(outputFile);
        } catch (FileNotFoundException e) {
            ExceptionHandler.abort(e, "Output file not specified correctly.");
        }

        //******************************************************************
        //Specify the output .CSV file that will collect the resulting tuples after extraction from OSM (if requested)
        if (currentConfig.isCsvOutput()) {
            try {
                csvWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(FilenameUtils.removeExtension(outputFile) + ".csv"), StandardCharsets.UTF_8));
                csvWriter.write(Constants.OUTPUT_CSV_HEADER + "|" + String.join("|", cols) + "|" + Constants.OTHER_TAGS_COLUMN);   //Custom header specified in Constants
                csvWriter.newLine();
            } catch (Exception e) {
                ExceptionHandler.abort(e, "Output CSV file not specified correctly.");
            }
        }
        //******************************************************************

        //******************************************************************
        //CAUTION! Optionally, the very same records are also written directly into an SQLite database file (with a .poi suffix)
        if (currentConfig.isSqliteOutput()) {
            try {
                sqliteWriter = new SqliteWriter(SqliteWriter.dbFilePath(outputFile), SqliteWriter.buildHeaders(cols));
            } catch (Exception e) {
                ExceptionHandler.abort(e, "Output SQLite file not specified correctly.");
            }
        }
        //******************************************************************

        //CAUTION! Hard constraint: serialization into N-TRIPLES is only supported by Jena riot (stream) interface
        stream = StreamRDFWriter.getWriterStream(outFile, Lang.NT);
        stream.start();             //Start issuing streaming triples
    }


    /**
     * Provides triples resulted after applying transformation against a single input feature or a small batch of features.
     * Applicable in STREAM transformation mode.
     *
     * @return A collection of RDF triples.
     */
    public List<Triple> getTriples() {

        return results;
    }

    /**
     * Parses a single OSM record and streamlines the resulting triples (including geometric and non-spatial attributes).
     * Applicable in STREAM transformation mode.
     * Input provided as an individual record. This method is used for input from OpenStreetMap XML/PBF files.
     *
     * @param rs         Representation of an OSM record with attributes extracted from an OSM element (node, way, or relation).
     * @param classific  Instantiation of the classification scheme that assigns categories to input features.
     * @param reproject  CRS transformation parameters to be used in reprojecting a geometry to a target SRID (EPSG code).
     * @param targetSRID Spatial reference system (EPSG code) of geometries in the output RDF triples.
     */
    public void parse(OSMRecord rs, Classification classific, MathTransform reproject, int targetSRID) {
        try {
            ++numRec;
            //CAUTION! On-the-fly generation of a UUID for this feature, giving as seed the data source and the identifier of that feature
            //String uuid = myAssistant.getUUID(currentConfig.featureSource, rs.getID()).toString();

            //CAUTION! These variables are only used when also creating a .CSV file with all OSM attributes
            double lon = 0;
            double lat = 0;
            String category = Constants.REGISTRY_CSV_DELIMITER;

            //Parse geometric representation
            String wkt = null;
            if ((rs.getGeometry() != null) && (!rs.getGeometry().isEmpty())) {
                lon = myAssistant.getLongitude(rs.getGeometry());    //CAUTION! These coordinate values only used for .CSV output
                lat = myAssistant.getLatitude(rs.getGeometry());

                wkt = rs.getGeometry().toText();       //Get WKT representation
                if (!myAssistant.filterContains(wkt)) {
                    //Apply spatial filtering (if specified by user)
                    if (!myAssistant.filterContains(wkt)) {
                        rejectedRec++;
                        return;
                    }

                    //CRS transformation
                    if (reproject != null)
                        wkt = myAssistant.wktTransform(wkt, reproject);     //Get transformed WKT representation
//				    else	
//				      	myAssistant.WKT2Geometry(wkt);                      //This is done only for updating the MBR of all geometries
                }
            } else {
                rejectedRec++;
                return;
            }

            //Tags to be processed as attribute values
            Map<String, String> tags = rs.getTagKeyValue();
            Map<String, String> attrValues = new HashMap<String, String>(rs.getTagKeyValue());

            //Include standard attributes for OSM identifier, name, and type
            attrValues.put("osm_id", rs.getID());
            attrValues.put("name", rs.getName());
            attrValues.put("type", rs.getType());

            //Skip transformation of any features filtered out by the logical expression over thematic attributes
            if (myAssistant.filterThematic(attrValues)) {
                rejectedRec++;
                return;
            }

            //Include identified category in these tags as an extra attribute
            if (rs.getCategory() != null) {
                //CAUTION! Only used for .CSV output
                category = (rs.getCategory().contains("_")) ? rs.getCategory().replace("_", Constants.REGISTRY_CSV_DELIMITER) : rs.getCategory() + Constants.REGISTRY_CSV_DELIMITER;

                //Attribute to be used in the Registry as well
                //Ad-hoc name for this extra attribute
                attrValues.put(Objects.requireNonNullElse(currentConfig.attrCategory, "OSM_Category"), rs.getCategory());
            } else {
                rejectedRec++;
                return;          //CAUTION! Do not proceed to transform unless this feature complies with the filtering tags specified by the user
            }

            //*********************************CSV specific****************************
            //Process all tags and distinguish attribute values to export to CSV
            ArrayList<String> outArr = new ArrayList<String>();
            outArr.add(rs.getID());
            outArr.add(rs.getName());
            outArr.add(category);
            outArr.add(Double.toString(lon));
            outArr.add(Double.toString(lat));
            outArr.add(Integer.toString(targetSRID));
            outArr.add(rs.getGeometry().toText());

            Map<String, String> tagMapRec = new HashMap<>();
            for (String col : cols) {
                tagMapRec.put(col, "");
            }

            for (Map.Entry<String, String> entry : tagMap.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                String tagFieldValue = tags.getOrDefault(key, "");
                String existingValue = tagMapRec.get(value);

                if (Objects.equals(existingValue, "")) {
                    tagMapRec.put(value, tagFieldValue);
                } else {
                    if (!Objects.equals(tagFieldValue, ""))
                        tagMapRec.put(value, existingValue + ";" + tagFieldValue);
                }

                tags.remove(key);
            }

            for (String col : cols) {
                outArr.add(formatString(tagMapRec.get(col)));
            }

            String othertags = formatString(mapperObj.writeValueAsString(rs.getTagKeyValue()));
            outArr.add(othertags);

            if (csvWriter != null) {
                csvWriter.write(String.join("|", outArr));
                csvWriter.newLine();
            }

            //CAUTION! Store the very same record directly into the SQLite database file as well (if requested)
            if (sqliteWriter != null)
                sqliteWriter.insert(SqliteWriter.fromCsvRecord(outArr));
            //***************************************************************************

            //Process all available non-spatial attributes as specified in the collected (tag,value) pairs
            //... including a classification hierarchy from the OSM tags used in filtering
            String uri = myGenerator.transform(attrValues, wkt, targetSRID, classific);

            //Periodically, collect RDF triples resulting from this batch and dump results into output file
            if (numRec % currentConfig.batch_size == 0) {
                collectTriples();
                myAssistant.notifyProgress(numRec);
            }

        } catch (Exception e) {
            LOGGER.warn("Problem at element with OSM id: {}. Excluded from transformation.", rs.getID());
        } finally {
            collectTriples();     //Dump any pending results into output file
        }
    }


    /**
     * Collects RDF triples generated from a batch of features (their thematic attributes and their geometries) and streamlines them to output file.
     */
    private void collectTriples() {
        try {
            //Append each triple to the output stream
            for (int i = 0; i <= myGenerator.getTriples().size() - 1; i++) {
                stream.triple(myGenerator.getTriples().get(i));
                numTriples++;
            }

            //Clean up RDF triples, in order to collect the new ones derived from the next batch of features
            myGenerator.clearTriples();
        } catch (Exception e) {
            ExceptionHandler.warn(e, "An error occurred during transformation of an input record.");
        }
    }


    /**
     * Finalizes storage of resulting tuples into a file.
     *
     * @param outputFile Path to the output file that collects RDF triples.
     */
    public void store(String outputFile) {
        stream.finish();               //Finished issuing triples

        //******************************************************************
        //Close the file that will collect all CSV tuples
        try {
            if (csvWriter != null)
                csvWriter.close();
        } catch (IOException e) {
            ExceptionHandler.abort(e, "An error occurred during creation of the output CSV file.");
        }
        //******************************************************************

        //******************************************************************
        //Finalize the SQLite database file that has collected the same records (if any)
        if (sqliteWriter != null) {
            sqliteWriter.close();
            if (sqliteWriter.getNumInserted() > 0)
                LOGGER.info("{} records stored into SQLite database file: {}", sqliteWriter.getNumInserted(), sqliteWriter.getDbFile());
        }
        //******************************************************************

        //Measure execution time and issue statistics on the entire process
        dt = System.currentTimeMillis() - t_start;
        myAssistant.reportStatistics(dt, numRec, rejectedRec, numTriples, currentConfig.serialization, myGenerator.getStatistics(), myGenerator.getMBR(), currentConfig.mode, currentConfig.targetCRS, outputFile, 0);
    }

}
