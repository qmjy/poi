/*
 * @(#) Configuration.java 	 version 2.1   19/10/2026
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration of the transformation of geospatial features into CSV records and (optionally) RDF triples.
 * <p>
 * CAUTION! Configuration is no longer read from an external properties (.conf) file.
 * All static settings are loaded from the built-in classpath resource {@value #CONFIG_RESOURCE}
 * (i.e. src/main/resources/application.properties), whereas runtime settings are passed as command-line arguments:
 * <ol>
 *     <li>inputFilePath - path of the OSM file to be parsed</li>
 *     <li>inputFileType - format of that file: OSM_PBF or OSM_XML</li>
 * </ol>
 * The runtime data path is no longer an argument: it is the directory that holds the input file.
 * Output files are written directly into that directory, whereas the temporary directory is created
 * underneath it and is removed once the transformation is over.
 *
 * @author Kostas Patroumpas
 * @version 2.2
 */
public final class Configuration {

    /**
     * Classpath location of the built-in configuration file.
     */
    public static final String CONFIG_RESOURCE = "application.properties";

    /**
     * Supported input format: OpenStreetMap data in compressed PBF format.
     */
    public static final String FORMAT_OSM_PBF = "OSM_PBF";

    /**
     * Supported input format: OpenStreetMap data in XML format.
     */
    public static final String FORMAT_OSM_XML = "OSM_XML";

    /**
     * Default name of the directory (relative to the runtime data path) that holds intermediate files.
     */
    public static final String DEFAULT_TMP_DIR = "tmp";

    Assistant myAssistant;

    /**
     * Execution platform at runtime: JVM (default).
     */
    public String runtime = "JVM";

    /**
     * Transformation mode: (disk-based) GRAPH, (in-memory) STREAM, (in-memory) XSLT, or RML.
     */
    public String mode;

    /**
     * Format of input data. Supported formats: OSM_PBF, OSM_XML.
     */
    public String inputFormat;

    /**
     * Path to the input dataset, as specified in the first command-line argument.
     */
    public String inputFiles;

    /**
     * Runtime data path: the directory holding the input dataset. It is derived from the first command-line argument.
     */
    public String dataPath;

    /**
     * Path to a directory where intermediate files may be temporarily written during transformation.
     * It is composed as: dataPath + tmpDir (as configured in {@value #CONFIG_RESOURCE}) and is discarded after the transformation.
     */
    public String tmpDir;

    /**
     * Path to the directory where output files will be written. By convention, output files have the same name as their corresponding input dataset.
     * By default it coincides with dataPath (i.e. the directory of the input file); otherwise it is composed as dataPath + outputDir.
     */
    public String outputDir;

    /**
     * Path to Attributes mapping File (OSM tags to CSV columns).
     * CAUTION! This file is bundled within the application (classpath resource referenced in {@value #CONFIG_RESOURCE}),
     * so it is no longer supplied as an external input.
     */
    public String mapping_file;

    /**
     * Default number of entities (i.e., records) to handle in each batch (applicable to STREAM mode).
     */
    public int batch_size = 10;

    /**
     * Path to a file containing mappings of attributes from input schema to RDF properties.
     */
    public String mappingSpec;

    /**
     * Path to a file specifying a classification hierarchy with categories assigned to features in the dataset.
     */
    public String classificationSpec;

    /**
     * Specifies whether the join attribute with the classification scheme is based on the category identifier (false) or the actual name of the category (true).
     * By default, transformation uses identifiers of categories in the classification scheme. This parameter has no effect if no classification hierarchy has been specified.
     */
    public boolean classifyByName = false;

    /**
     * Output RDF serialization. Supported formats: RDF/XML (default), RDF/XML-ABBREV, N-TRIPLES, TURTLE (or TTL), N3.
     */
    public String serialization;

    /**
     * Specifies the spatial ontology for geometries in the exported RDF data.
     * Possible values: 1) "GeoSPARQL", 2) "Virtuoso" (legacy RDF ontology for points only), 3) "wgs84_pos" (for WGS84 Geoposition RDF vocabulary).
     */
    public String targetGeoOntology;

    /**
     * List of prefixes for namespaces employed in the transformation.
     */
    public String[] prefixes;

    /**
     * List of namespaces corresponding to the prefixes.
     */
    public String[] namespaces;

    /**
     * Ontology namespace of each transformed feature.
     */
    public String ontologyNS = "http://slipo.eu/def#";

    /**
     * Geometry namespace for WKT literals and spatial properties.
     */
    public String geometryNS = "http://www.opengis.net/ont/geosparql#";

    /**
     * Namespace for feature URIs.
     */
    public String featureNS = "http://slipo.eu/id/poi/";

    /**
     * Namespace for the URIs of the category assigned to each feature.
     */
    public String featureClassNS = "http://slipo.eu/id/term/";

    /**
     * Namespace for the classification scheme employed for the features.
     */
    public String featureClassificationNS = "http://slipo.eu/id/classification/";

    /**
     * Namespace for the data source URI assigned to each feature.
     */
    public String featureSourceNS = "http://slipo.eu/id/poisource/";

    /**
     * Default language specification tag for string literals.
     */
    public String defaultLang = "en";


    /**
     * SQL filter to be applied on the database table; also, thematic filter (in SQL syntax) over geographical files.
     */
    public String filterSQLCondition = null;

    /**
     * Spatial filter to be applied on the input dataset.
     */
    public String spatialExtent = null;


    /**
     * Indicates whether to ignore or retain unnamed entities during transformation.
     * CAUTION! This functionality is applicable in the context of OSM features only.
     */
    public boolean keepUnnamedEntities = false;

    /**
     * Indicates whether the extracted records will also be written directly into an SQLite database file ending with ".poi".
     * This setting is read from {@value #CONFIG_RESOURCE}; it is not supplied as an external input any more.
     */
    public boolean sqliteOutput = true;


    /**
     * Encoding used for strings.
     * Default encoding: UTF-8.
     */
    public String encoding;

    /**
     * Spatial reference system (EPSG code) of the input dataset. If omitted, geometries are assumed in WGS84 reference system (EPSG:4326).
     * Example: EPSG:2100
     */
    public String sourceCRS;

    /**
     * Spatial reference system (EPSG code) of geometries in the output RDF triples. If omitted, RDF geometries will retain their original georeference (i.e., no reprojection of coordinates will take place).
     * Example: EPSG:4326
     */
    public String targetCRS;

    /**
     * Name of the input attribute containing a unique identifier of each feature.
     */
    public String attrKey;

    /**
     * Name of the attribute containing the geometry of a feature.
     */
    public String attrGeometry;

    /**
     * Name of the attribute specifying the main name of a feature.
     */
    public String attrName;

    /**
     * Name of the attribute specifying the category of a feature according to a classification scheme.
     */
    public String attrCategory;

    /**
     * Name of the attribute containing the X-ordinate (or longitude) of a feature.
     * Ignored in case that attrGeometry has been specified.
     */
    public String attrX;

    /**
     * Name of the attribute containing the Y-ordinate (or latitude) of a feature.
     * Ignored in case that attrGeometry has been specified.
     */
    public String attrY;

    /**
     * String specifying the data source (provider) of the input dataset(s).
     */
    public String featureSource;

    /**
     * Constructor of a Configuration object.
     * All static parameters are loaded from the built-in classpath resource {@value #CONFIG_RESOURCE},
     * then they are combined with the runtime arguments provided on the command line.
     * CAUTION! The data path is no longer supplied as an argument: the directory holding the input file is used instead.
     *
     * @param inputFilePath Path to the OSM file to be parsed (1st command-line argument).
     * @param inputFileType Format of that file, either OSM_PBF or OSM_XML (2nd command-line argument).
     */
    public Configuration(String inputFilePath, String inputFileType) {

        myAssistant = new Assistant();
        initializeParameters(loadEmbeddedConfiguration());
        applyRuntimeArguments(inputFilePath, inputFileType);
    }

    /**
     * Loads the built-in configuration from the classpath resource {@value #CONFIG_RESOURCE}.
     *
     * @return All properties as bundled with the application.
     */
    private Properties loadEmbeddedConfiguration() {

        Properties properties = new Properties();
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IOException("Resource " + CONFIG_RESOURCE + " is missing from the application classpath.");
            }
            //CAUTION! Read as UTF-8 so that comments written in the properties file are also handled correctly
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException io) {
            ExceptionHandler.abort(io, "Problems loading the embedded configuration file " + CONFIG_RESOURCE + ".");
        }
        return properties;
    }

    /**
     * Applies the runtime arguments supplied on the command line.
     * The input file path and its format are taken verbatim from the arguments,
     * whereas the runtime data path is derived from the directory that holds the input file.
     * By default, output files are written directly into that directory, while the temporary directory
     * is created underneath it as configured (and is discarded once the transformation is over).
     *
     * @param inputFilePath Path to the OSM file to be parsed (1st command-line argument).
     * @param inputFileType Format of that file, either OSM_PBF or OSM_XML (2nd command-line argument).
     */
    private void applyRuntimeArguments(String inputFilePath, String inputFileType) {

        //Input dataset to be parsed
        if (myAssistant.isNullOrEmpty(inputFilePath) || myAssistant.isNullOrEmpty(inputFilePath.trim())) {
            throw new IllegalArgumentException("No input file specified.");
        }
        inputFiles = inputFilePath.trim();

        //Format of the input dataset
        String type = (inputFileType == null) ? "" : inputFileType.trim().toUpperCase();
        switch (type) {
            case FORMAT_OSM_PBF, "PBF" -> inputFormat = FORMAT_OSM_PBF;
            case FORMAT_OSM_XML, "XML" -> inputFormat = FORMAT_OSM_XML;
            default -> throw new IllegalArgumentException("Unsupported input file type: " + inputFileType
                    + ". Supported values: " + FORMAT_OSM_PBF + ", " + FORMAT_OSM_XML + ".");
        }

        //Runtime data path: the directory where the input file resides (no separate dataPath argument any more)
        File input = new File(inputFiles).getAbsoluteFile();
        File parent = input.getParentFile();
        Path root = (parent == null) ? Paths.get(".").toAbsolutePath().normalize() : parent.toPath().normalize();
        dataPath = root.toString();

        //Temporary directory: appended to the data path; it will be removed after the transformation
        tmpDir = resolvePath(root, tmpDir, DEFAULT_TMP_DIR).toString();

        //Output directory: by default (empty setting) it coincides with the data path, i.e. next to the input file
        String configuredOutput = (outputDir == null) ? "" : outputDir.trim();
        Path out = (myAssistant.isNullOrEmpty(configuredOutput) || ".".equals(configuredOutput))
                ? root : resolvePath(root, configuredOutput, configuredOutput);
        outputDir = appendTrailingSlash(out.toString());
    }

    /**
     * Resolves a configured directory against the runtime data path.
     * Absolute directory names are retained as they are; relative ones are appended to the data path.
     *
     * @param root        Runtime data path (3rd command-line argument).
     * @param dir         Directory name as configured in the embedded configuration file.
     * @param defaultName Directory name to be used if none has been configured.
     * @return The resulting (normalized) path.
     */
    private Path resolvePath(Path root, String dir, String defaultName) {

        String name = (myAssistant.isNullOrEmpty(dir) || myAssistant.isNullOrEmpty(dir.trim())) ? defaultName : dir.trim();
        Path path = Paths.get(name);
        return (path.isAbsolute() ? path : root.resolve(path)).normalize();
    }

    /**
     * Appends a trailing slash to the given directory path (if not already present), in order to correctly create the paths of output files.
     *
     * @param dir Path to a directory.
     * @return The same path with a trailing '/' (or the original one if it already ends with a separator).
     */
    private String appendTrailingSlash(String dir) {

        if (dir.endsWith("/") || dir.endsWith("\\"))
            return dir;
        return dir + "/";   //Always safe to use '/' instead of File.separator in any OS
    }

    /**
     * Opens the attribute mapping file (OSM tags to CSV columns) designated in the embedded configuration.
     * The file is first looked up in the classpath (default location: src/main/resources/mapping),
     * and if not found there, it is treated as a file system path.
     *
     * @return An input stream over the contents of the attribute mapping file.
     * @throws IOException In case that no such file exists.
     */
    public InputStream openMappingFile() throws IOException {

        if (myAssistant.isNullOrEmpty(mapping_file) || myAssistant.isNullOrEmpty(mapping_file.trim())) {
            throw new IOException("No attribute mapping file has been configured (" + CONFIG_RESOURCE + ": mapping_file).");
        }

        String mapping = mapping_file.trim();
        String resource = mapping.replace('\\', '/');
        if (resource.startsWith("/"))
            resource = resource.substring(1);

        //OPTION #1: The mapping file is bundled within the application (default)
        InputStream in = Configuration.class.getClassLoader().getResourceAsStream(resource);
        if (in != null)
            return in;

        //OPTION #2: The mapping file has been given as an external path
        File file = new File(mapping);
        if (file.isFile())
            return new FileInputStream(file);

        throw new IOException("Attribute mapping file not found: " + mapping);
    }

    /**
     * Initializes all the parameters for the transformation from the built-in configuration file.
     *
     * @param properties    All properties as specified in the configuration file.
     */
    private void initializeParameters(Properties properties) {

        //Conversion mode: (in-memory) STREAM
        if (!myAssistant.isNullOrEmpty(properties.getProperty("mode"))) {
            mode = properties.getProperty("mode").trim();
        }

        //File specification properties: directory names that will be appended to the runtime data path
        if (!myAssistant.isNullOrEmpty(properties.getProperty("outputDir"))) {
            outputDir = properties.getProperty("outputDir").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("tmpDir"))) {
            tmpDir = properties.getProperty("tmpDir").trim();
        }

        //Number of entities (i.e., records) to handle in each batch; this actually controls how frequently the results are written to file
        if (!myAssistant.isNullOrEmpty(properties.getProperty("batchSize"))) {
            try {
                batch_size = Integer.parseInt(properties.getProperty("batchSize").trim());
                //Apply the default value in case of invalid settings
                if ((batch_size < 1) || (batch_size > 1000))
                    batch_size = 10;
            } catch (Exception e) {
                ExceptionHandler.abort(e, "Incorrect value set for batch size. Please specify a positive integer value in your configuration file.");
            }
        }

        //Path to a file containing attribute mappings from input schema to RDF properties
        if (!myAssistant.isNullOrEmpty(properties.getProperty("mappingSpec"))) {
            mappingSpec = properties.getProperty("mappingSpec").trim();
        }

        //Path to a file containing attribute mappings from input schema to CSV columns (bundled in the classpath by default)
        if (!myAssistant.isNullOrEmpty(properties.getProperty("mapping_file"))) {
            mapping_file = properties.getProperty("mapping_file").trim();
        }

        //Path to a file specifying a classification hierarchy with categories assigned to features in the dataset
        if (!myAssistant.isNullOrEmpty(properties.getProperty("classificationSpec"))) {
            classificationSpec = properties.getProperty("classificationSpec").trim();
        }

        //Output RDF serialization property
        if (!myAssistant.isNullOrEmpty(properties.getProperty("serialization"))) {
            serialization = properties.getProperty("serialization").trim();
        }

        //Spatial ontology of RDF geometries: GeoSPARQL, Virtuoso, or WGS84 Geoposition RDF vocabulary
        if (!myAssistant.isNullOrEmpty(properties.getProperty("targetGeoOntology"))) {
            targetGeoOntology = properties.getProperty("targetGeoOntology").trim();
        }

        //NAMESPACE specification properties
        //Ontology namespace
        if (!myAssistant.isNullOrEmpty(properties.getProperty("nsOntology"))) {
            ontologyNS = properties.getProperty("nsOntology").trim();
        }
        //Geometry namespace
        if (!myAssistant.isNullOrEmpty(properties.getProperty("geometryNS"))) {
            geometryNS = properties.getProperty("geometryNS").trim();
        }
        //Namespace for feature URIs
        if (!myAssistant.isNullOrEmpty(properties.getProperty("nsFeatureURI"))) {
            featureNS = properties.getProperty("nsFeatureURI").trim();
        }
        //Namespace for the category URIs assigned to each feature
        if (!myAssistant.isNullOrEmpty(properties.getProperty("nsClassURI"))) {
            featureClassNS = properties.getProperty("nsClassURI").trim();
        }
        //Namespace for the classification scheme
        if (!myAssistant.isNullOrEmpty(properties.getProperty("nsClassificationURI"))) {
            featureClassificationNS = properties.getProperty("nsClassificationURI").trim();
        }
        //Namespace for the data source URIs assigned to each feature
        if (!myAssistant.isNullOrEmpty(properties.getProperty("nsDataSourceURI"))) {
            featureSourceNS = properties.getProperty("nsDataSourceURI").trim();
        }

        //Reconstruct correspondence of prefixes and namespaces
        if (!myAssistant.isNullOrEmpty(properties.getProperty("prefixes"))) {
            prefixes = properties.getProperty("prefixes").split(",");
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("namespaces"))) {
            namespaces = properties.getProperty("namespaces").split(",");
        }

        //Feature and attribute properties
        if (!myAssistant.isNullOrEmpty(properties.getProperty("filterSQLCondition"))) {
            filterSQLCondition = properties.getProperty("filterSQLCondition").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("attrKey"))) {
            attrKey = properties.getProperty("attrKey").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("attrGeometry"))) {
            attrGeometry = properties.getProperty("attrGeometry").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("attrCategory"))) {
            attrCategory = properties.getProperty("attrCategory").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("attrName"))) {
            attrName = properties.getProperty("attrName").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("attrX"))) {
            attrX = properties.getProperty("attrX").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("attrY"))) {
            attrY = properties.getProperty("attrY").trim();
        }

        //Topological filter applied over input dataset (except for database tables)
        if (!myAssistant.isNullOrEmpty(properties.getProperty("spatialExtent"))) {
            spatialExtent = properties.getProperty("spatialExtent").trim();
        }

        //CUSTOM: Indicates whether to retain or ignore unnamed entities during transformation
        if ((properties.containsKey("keepUnnamedEntities")) && (!myAssistant.isNullOrEmpty(properties.getProperty("keepUnnamedEntities").trim()))) {
            keepUnnamedEntities = Boolean.parseBoolean(properties.getProperty("keepUnnamedEntities").trim());
        }

        //CUSTOM: Indicates whether the extracted records will also be written directly into an SQLite database file (*.poi)
        if ((properties.containsKey("sqliteOutput")) && (!myAssistant.isNullOrEmpty(properties.getProperty("sqliteOutput").trim()))) {
            sqliteOutput = Boolean.parseBoolean(properties.getProperty("sqliteOutput").trim());
        }

        //Encoding of data values
        encoding = StandardCharsets.UTF_8.name();                   //Default encoding
        if (!myAssistant.isNullOrEmpty(properties.getProperty("encoding"))) {
            if (Charset.isSupported(properties.getProperty("encoding").trim()))
                encoding = Charset.forName(properties.getProperty("encoding").trim()).name();
            else
                System.err.println("Specified encoding " + properties.getProperty("encoding").trim() + " is not recognized.");
        } else
            System.out.print("No encoding specified. Utilizing default ");

        System.out.println("Encoding: " + encoding);

        //Spatial reference system transformation properties
        if (!myAssistant.isNullOrEmpty(properties.getProperty("sourceCRS"))) {
            sourceCRS = properties.getProperty("sourceCRS").trim();
        }
        if (!myAssistant.isNullOrEmpty(properties.getProperty("targetCRS"))) {
            targetCRS = properties.getProperty("targetCRS").trim();
        }

        //Default language specification tag for string literals
        if (!myAssistant.isNullOrEmpty(properties.getProperty("defaultLang"))) {
            defaultLang = properties.getProperty("defaultLang").trim();
        }

        //Data source (provider) of the input dataset(s)
        if (!myAssistant.isNullOrEmpty(properties.getProperty("featureSource"))) {
            featureSource = properties.getProperty("featureSource").trim();
        }

        //CAUTION! The input file and its format are supplied as command-line arguments, so they are not read from the configuration file
    }

}
