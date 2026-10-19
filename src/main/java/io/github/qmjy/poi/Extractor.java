/*
 * @(#) Extractor.java	version 2.1   19/10/2026
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
package io.github.qmjy.poi;

import io.github.qmjy.poi.tools.OsmPbfParser;
import io.github.qmjy.poi.tools.OsmXmlParser;
import io.github.qmjy.poi.utils.Assistant;
import io.github.qmjy.poi.utils.Configuration;
import io.github.qmjy.poi.utils.Constants;
import io.github.qmjy.poi.utils.ExceptionHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point to OSMWrangle for converting spatial features extracted from OpenStreetMap files (either XML or PBF formats) into CSV records and/or RDF triples.
 * <p>
 * Execution command over JVM:
 * <pre>
 * java -cp target/poi-1.0.0-SNAPSHOT.jar io.github.qmjy.poi.Extractor &lt;filePath&gt; &lt;fileType&gt; [outputType]
 * </pre>
 * Arguments:
 * <ol>
 *     <li>filePath - path of the OSM file to be parsed, e.g. ./test/data/latest.osm.pbf</li>
 *     <li>fileType - format of that file: OSM_PBF or OSM_XML</li>
 *     <li>outputType - optional kind of output: 0 = CSV + SQLite (default), 1 = SQLite only, 2 = CSV only</li>
 * </ol>
 * The runtime data path is the directory of the input file: results are written next to it,
 * whereas the temporary directory created underneath it is removed once the process terminates.
 * CAUTION! Static settings (attribute mapping file, etc.) are read from the classpath resource
 * application.properties; no external configuration file is used any more.
 * The output type is no longer part of that configuration: it is supplied on the command line.
 *
 * @author Kostas Patroumpas
 * @version 2.2
 */
public class Extractor {

    /**
     * SLF4J logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Extractor.class);


    static Assistant myAssistant;
    static int sourceSRID;                              //Source CRS according to EPSG
    static int targetSRID;                              //Target CRS according to EPSG

    //Guarantees that the temporary directory is removed only once, whether the JVM exits normally or abnormally
    private static final AtomicBoolean temporaryDirectoryDiscarded = new AtomicBoolean(false);


    public static void main(String[] args) {
        String inFile;
        String outFile;

        boolean failure = true;                        //Indicates whether transformation has failed to conclude

        //CAUTION! Two arguments are mandatory (<filePath> <fileType>); the 3rd one (<outputType>) is optional
        if (args.length >= 2) {

            String[] runtimeArgs;
            try {
                //Runtime settings supplied as key-value arguments (positional form is also accepted)
                runtimeArgs = parseArguments(args);
            } catch (IllegalArgumentException iae) {
                LOGGER.error(iae.getMessage());
                printUsage();
                System.exit(1);          //Execution terminated abnormally
                return;
            }

            String filePath = runtimeArgs[0];           //1st argument: path of the OSM file to be parsed
            String fileType = runtimeArgs[1];           //2nd argument: format of that file (OSM_PBF or OSM_XML)
            String outputType = runtimeArgs[2];         //3rd (optional) argument: kind of output (0: CSV + SQLite, 1: SQLite, 2: CSV)

            //Check that the input file actually exists
            File inputFile = new File(filePath);
            if (!inputFile.isFile()) {
                LOGGER.error("Input file not found: {}", filePath);
                printUsage();
                System.exit(1);          //Execution terminated abnormally
                return;
            }

            //Configuration settings for the transformation: built-in settings combined with the runtime arguments
            Configuration currentConfig;
            try {
                currentConfig = new Configuration(filePath, fileType, outputType);
            } catch (IllegalArgumentException iae) {
                LOGGER.error(iae.getMessage());
                printUsage();
                System.exit(1);          //Execution terminated abnormally
                return;
            }

            //Default conversion mode: (in-memory) STREAM
            currentConfig.mode = "STREAM";

            //Force N-TRIPLES serialization since the STREAM mode is aplied
            //currentConfig.serialization = "N-TRIPLES";

            myAssistant = new Assistant(currentConfig);

            System.setProperty("org.geotools.referencing.forceXY", "true");

            //Ensure that the temporary directory underneath the data path does exist (the output directory is the data path itself)
            prepareDirectory(currentConfig.tmpDir, "temporary");

            //CAUTION! Register cleanup of the temporary directory also on abnormal termination
            //(System.exit does not execute finally blocks, whereas ExceptionHandler.abort relies on it)
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> discardTemporaryDirectory(currentConfig.tmpDir, currentConfig.dataPath), "tmp-cleanup"));

            //A SINGLE input OSM file name must be specified
            inFile = currentConfig.inputFiles;
            outFile = currentConfig.outputDir + FilenameUtils.getBaseName(inFile) + ".nt";     //CAUTION! Output file for RDF triples is in NT; another one will be always created in CSV format

            sourceSRID = 0;                                   //Non-specified, so...
            LOGGER.info(Constants.WGS84_PROJECTION);   //... all features are assumed in WGS84 lon/lat coordinates

            long start = System.currentTimeMillis();
            try {
                //Apply data transformation according to the input format specified in the 2nd argument
                if (Configuration.FORMAT_OSM_XML.equalsIgnoreCase(currentConfig.inputFormat)) {          //OpenStreetMap data in XML format
                    OsmXmlParser conv = new OsmXmlParser(currentConfig, inFile, outFile, sourceSRID, targetSRID);
                    conv.apply();
                    failure = false;
                } else if (Configuration.FORMAT_OSM_PBF.equalsIgnoreCase(currentConfig.inputFormat)) {  //OpenStreetMap data in PBF format
                    OsmPbfParser conv = new OsmPbfParser(currentConfig, inFile, outFile, sourceSRID, targetSRID);
                    conv.apply();
                    conv.close();
                    failure = false;
                } else {
                    throw new IllegalArgumentException(Constants.INCORRECT_SETTING);
                }
            } catch (Exception e) {
                ExceptionHandler.abort(e, Constants.INCORRECT_SETTING);      //Execution terminated abnormally
            } finally {
                long elapsed = System.currentTimeMillis() - start;
                //Cleanup intermediate files and remove the temporary directory: it must not be retained after the transformation
                discardTemporaryDirectory(currentConfig.tmpDir, currentConfig.dataPath);
                if (failure) {
                    LOGGER.error("Transformation process failed. Elapsed time: {} ms.", elapsed);
                    System.exit(1);          //Execution failed in at least one task
                } else {
                    LOGGER.info("Transformation process concluded successfully in {} ms.", elapsed);
                    LOGGER.info("Results written in this directory: {}", currentConfig.outputDir);
                    System.exit(0);          //Execution completed successfully
                }
            }
        } else {
            LOGGER.error(Constants.INCORRECT_CONFIG);
            printUsage();
            System.exit(1);          //Execution terminated abnormally
        }
    }

    /**
     * Removes the temporary directory (holding disk-based indices and other intermediate files) once the transformation is over.
     * CAUTION! If that directory coincides with the data path (or is one of its parent directories), no deletion takes place;
     * in such a case only the intermediate files inside it are cleared.
     *
     * @param tmpDir   Path to the temporary directory.
     * @param dataPath Runtime data path (directory of the input file).
     */
    private static void discardTemporaryDirectory(String tmpDir, String dataPath) {

        if (myAssistant == null || myAssistant.isNullOrEmpty(tmpDir)) {
            return;
        }
        if (!temporaryDirectoryDiscarded.compareAndSet(false, true)) {
            return;                      //Already handled (e.g. by the shutdown hook)
        }

        try {
            Path tmp = Paths.get(tmpDir).toAbsolutePath().normalize();
            Path data = Paths.get(dataPath).toAbsolutePath().normalize();

            //Safety guard: never remove the data path itself (nor any of its parent directories)
            if (tmp.equals(data) || data.startsWith(tmp)) {
                myAssistant.cleanupFilesInDir(tmpDir);       //Only clear intermediate files
                LOGGER.info("Temporary directory coincides with the data path; contents cleared but directory retained: {}", tmp);
                return;
            }

            //CAUTION! Retry a few times, as index files may still be held by the JVM shutdown sequence
            for (int attempt = 1; attempt <= 3 && Files.exists(tmp); attempt++) {
                try {
                    FileUtils.deleteDirectory(tmp.toFile());
                } catch (Exception e) {
                    if (attempt == 3)
                        throw e;
                }
                if (Files.exists(tmp))
                    Thread.sleep(200);
            }

            if (!Files.exists(tmp))
                LOGGER.info("Temporary directory removed: {}", tmp);
            else
                LOGGER.warn("Temporary directory could not be removed: {}", tmp);

        } catch (Exception e) {
            LOGGER.warn("Could not remove the temporary directory {}: {}", tmpDir, e.getMessage());
        }
    }

    /**
     * Creates the given directory (including any missing parent ones) in case it does not exist.
     *
     * @param dir  Path to the directory.
     * @param role Description of the role of this directory (used in messages).
     */
    private static void prepareDirectory(String dir, String role) {
        try {
            Path path = Paths.get(dir);
            if (!Files.isDirectory(path)) {
                Files.createDirectories(path);
                LOGGER.info("Created {} directory: {}", role, path);
            }
        } catch (Exception e) {
            ExceptionHandler.abort(e, "Cannot create the " + role + " directory: " + dir);
        }
    }

    /**
     * Parses the arguments supplied on the command line.
     * Both notations are accepted:
     * <ul>
     *     <li>positional: {@code <filePath> <fileType> [outputType]}</li>
     *     <li>key-value: {@code filePath=... fileType=... [outputType=...]} (order does not matter)</li>
     * </ul>
     * CAUTION! The data path is no longer an argument: the directory holding the input file is used instead.
     * Should a dataPath argument still be supplied (e.g. by an older script), it is ignored with a warning.
     *
     * @param args Arguments as given on the command line.
     * @return An array with the runtime settings: input file path, its format, and the output type (may be {@code null} for the default).
     * @throws IllegalArgumentException In case of missing or unknown arguments.
     */
    private static String[] parseArguments(String[] args) {

        String filePath = null;
        String fileType = null;
        String outputType = null;

        for (String arg : args) {
            String token = (arg == null) ? "" : arg.trim();
            if (token.isEmpty())
                continue;

            int pos = token.indexOf('=');
            if (pos > 0) {                       //Key-value form: filePath=./test/data/latest.osm.pbf
                String key = token.substring(0, pos).trim();
                String value = token.substring(pos + 1).trim();
                switch (key) {
                    case "filePath", "inputFile" -> filePath = value;
                    case "fileType", "inputFormat" -> fileType = value;
                    case "outputType", "output" -> outputType = value;
                    case "dataPath" -> LOGGER.warn("Argument dataPath is obsolete and will be ignored: "
                            + "the directory of the input file is always used as the data path.");
                    default -> throw new IllegalArgumentException("Unknown argument: " + key
                            + ". Expected keys: filePath, fileType, outputType.");
                }
            } else {                             //Positional form, in the order filePath, fileType, outputType
                if (filePath == null)
                    filePath = token;
                else if (fileType == null)
                    fileType = token;
                else if (outputType == null)
                    outputType = token;
                else
                    LOGGER.warn("Argument is obsolete and will be ignored: {} (only <filePath> <fileType> [outputType] are supported).", token);
            }
        }

        if (filePath == null || fileType == null) {
            throw new IllegalArgumentException("Incomplete arguments: both filePath and fileType are required.");
        }
        return new String[]{filePath, fileType, outputType};
    }

    /**
     * Prints the expected syntax of the command line.
     */
    private static void printUsage() {
        LOGGER.error("""
                        Usage: java -cp <classpath> {} <filePath> <fileType> [outputType]
                           or: java -cp <classpath> {} filePath=... fileType=... [outputType=...]
                          filePath   : Path to the OSM file to be parsed, e.g. ./test/data/latest.osm.pbf
                          fileType   : Format of that file, either {} or {}
                          outputType : Optional, kind of output to produce (default 0):
                                       0 = .csv and SQLite database file (*.poi)   [default]
                                       1 = SQLite database file (*.poi) only
                                       2 = .csv file only
                          (The directory of the input file is used as the data path: results are written next to the input file,
                           whereas the temporary directory is removed once the transformation is over.)
                          (Other settings, such as the attribute mapping file, are read from the embedded {})""",
                Extractor.class.getName(), Extractor.class.getName(),
                Configuration.FORMAT_OSM_PBF, Configuration.FORMAT_OSM_XML, Configuration.CONFIG_RESOURCE);
    }
}
