/*
 * @(#) Extractor.java	version 2.0   5/12/2019
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
import org.apache.commons.io.FilenameUtils;

/**
 * Entry point to OSMWrangle for converting spartial features extracted from OpenStreetMap files (either XML or PBF formats) into CSV
 * Execution command over JVM:
 * JVM:   java -cp target/osmwrangle-2.0-SNAPSHOT.jar eu.smartdatalake.athenarc.osmwrangle.Extractor <path-to-configuration-file>
 *
 * @author Kostas Patroumpas
 * @version 2.0
 */
public class Extractor {

    static Assistant myAssistant;
    static int sourceSRID;                              //Source CRS according to EPSG
    static int targetSRID;                              //Target CRS according to EPSG


    public static void main(String[] args) {
        String inFile;
        String outFile;

        boolean failure = true;                        //Indicates whether transformation has failed to conclude

        if (args.length > 0) {

            //Specify a configuration file with properties used in the conversion
            //Configuration settings for the transformation
            Configuration currentConfig = new Configuration(args[0]);          //Argument like "./bin/shp_options.conf"

            //Default conversion mode: (in-memory) STREAM
            currentConfig.mode = "STREAM";

            //Force N-TRIPLES serialization since the STREAM mode is aplied
            //currentConfig.serialization = "N-TRIPLES";

            myAssistant = new Assistant(currentConfig);

            System.setProperty("org.geotools.referencing.forceXY", "true");

            //Check how many input files have been specified
            if (currentConfig.inputFiles != null) {
                inFile = currentConfig.inputFiles;                                                  //A SINGLE input OSM file name must be specified
                outFile = currentConfig.outputDir + FilenameUtils.getBaseName(inFile) + ".nt";     //CAUTION! Output file for RDF triples is in NT; another one will be always created in CSV format
            } else {
                throw new IllegalArgumentException(Constants.INCORRECT_SETTING);
            }

            sourceSRID = 0;                                   //Non-specified, so...
            System.out.println(Constants.WGS84_PROJECTION);   //... all features are assumed in WGS84 lon/lat coordinates

            long start = System.currentTimeMillis();
            try {
                //Apply data transformation according to the given input format
                if (inFile.toUpperCase().trim().endsWith("OSM")) {            //OpenStreetMap data in XML format
                    OsmXmlParser conv = new OsmXmlParser(currentConfig, inFile, outFile, sourceSRID, targetSRID);
                    conv.apply();
                    failure = false;
                } else if (inFile.toUpperCase().trim().endsWith("PBF")) {        //OpenStreetMap data in PBF format
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
                myAssistant.cleanupFilesInDir(currentConfig.tmpDir);             //Cleanup intermediate files in the temporary directory
                if (failure) {
                    System.out.println(myAssistant.getGMTime() + String.format(" Transformation process failed. Elapsed time: %d ms.", elapsed));
                    System.exit(1);          //Execution failed in at least one task
                } else {
                    System.out.println(myAssistant.getGMTime() + String.format(" Transformation process concluded successfully in %d ms.", elapsed));
                    System.out.println("Results written in this directory:" + currentConfig.outputDir);
                    System.exit(0);          //Execution completed successfully
                }
            }
        } else {
            System.err.println(Constants.INCORRECT_CONFIG);
            System.exit(1);          //Execution terminated abnormally
        }
    }
}
