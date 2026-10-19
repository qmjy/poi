package io.github.qmjy.poi.utils;

/**
 * Constants utilized in the transformation and reverse transformation processes.
 *
 * @author Kostas Patroumpas
 * @version 2.0
 */

public class Constants {

  //REPLACEMENT value strings
  /**
   * Default line separator
   */
  public static final String LINE_SEPARATOR = "\n";      
  
  /**
   * String representation of UTF-8 encoding
   */
  public static final String UTF_8 = "UTF-8";           


  /**
   * Default header with the attribute names of the CSV file used for output features
   */
  public static final String OUTPUT_CSV_HEADER = "ID|NAME|CATEGORY|SUBCATEGORY|LON|LAT|SRID|WKT";  

  /**
   * Name of the column that collects in JSON format all OSM tags that have not been mapped to a dedicated column.
   * This column is appended at the end of the CSV file as well as of the SQLite database table.
   */
  public static final String OTHER_TAGS_COLUMN = "OTHER_TAGS";

  /**
   * Default extension of the SQLite database file where extracted records can be stored directly.
   */
  public static final String SQLITE_EXTENSION = ".poi";

  /**
   * Name of the table collecting the extracted records in the SQLite database file.
   */
  public static final String SQLITE_TABLE = "poi";
  
  /**
   * Suffix to URIs for geometries of features
   */
  public static final String GEO_URI_SUFFIX = "/geom"; 
  
  /**
   * Default delimiter of the CSV file used for registering features in the SLIPO Registry
   */
  public static final String REGISTRY_CSV_DELIMITER = "|";          

  /**
   * Index of URLs used in JDBC connections with each DBMS
   */
  public static final String[] BASE_URL = {"jdbc:ucanaccess:", "jdbc:mysql:", "jdbc:oracle:thin:", "jdbc:postgresql:", "jdbc:db2:", "jdbc:sqlserver:", "jdbc:sqlite:"};

  
  //ALIASES for most common namespaces 
  /**
   * Namespace for GeoSPARQL ontology
   */
  public static final String NS_GEO = "http://www.opengis.net/ont/geosparql#";   

  /**
   * Namespace for GeoSPARQL spatial features
   */
  public static final String NS_SF =  "http://www.opengis.net/ont/sf#";                               
  
  /**
   * Namespace for GML ontology
   */
  public static final String NS_GML = "http://loki.cae.drexel.edu/~wbs/ontology/2004/09/ogc-gml#";    

  /**
   * Namespace for XML Schema
   */
  public static final String NS_XSD = "http://www.w3.org/2001/XMLSchema#";                            
  
  /**
   * Namespace for RDF Schema
   */
  public static final String NS_RDFS = "http://www.w3.org/2000/01/rdf-schema#";  

  /**
   * Legacy namespace for WGS84 Geoposition RDF vocabulary
   */
  public static final String NS_POS = "http://www.w3.org/2003/01/geo/wgs84_pos#";   

  /**
   * Legacy namespace for Virtuoso RDF geometries
   */
  public static final String NS_VIRT = "http://www.openlinksw.com/schemas/virtrdf#";                  

  /**
   * Namespace for Dublin Core Metadata Initiative terms
   */
  public static final String NS_DC = "http://purl.org/dc/terms/";                                     
  
  
  //ALIASES for most common tags and properties for RDF triples
  public static final String GEOMETRY = "Geometry";
  public static final String FEATURE = "Feature";
  public static final String LATITUDE = "lat";
  public static final String LONGITUDE = "long";
  public static final String WKT = "asWKT";
  public static final String WKTLiteral = "wktLiteral";
  
  
  //Strings appearing in user notifications and warnings
  public static final String INCORRECT_CONFIG = "Incorrect number of arguments. Exactly two arguments are required: <filePath> <fileType>.";
  public static final String INCORRECT_CLASSIFICATION = "Incorrect number of arguments. Please specify a classification file in YML or CSV format, and a boolean value indicating whether classification hierarchy is based on category names (TRUE) or their identifiers (FALSE).";
  public static final String INCORRECT_SETTING = "Incorrect or no value set for at least one parameter. Please specify a correct value in the configuration settings.";
  public static final String WGS84_PROJECTION = "Input data is expected to be georeferenced in WGS84 (EPSG:4326).";
  
}
