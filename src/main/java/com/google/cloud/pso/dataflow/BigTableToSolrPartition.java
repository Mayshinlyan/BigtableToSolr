/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pso.dataflow;

import java.nio.ByteBuffer;
import java.util.Properties;
import java.io.*;
import java.lang.*;
import com.google.bigtable.v2.Cell;
import com.google.bigtable.v2.Column;
import com.google.bigtable.v2.Family;
import com.google.bigtable.v2.Row;
import com.google.protobuf.ByteString;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableIO;
import org.apache.beam.sdk.io.solr.SolrIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.PCollection;
import org.apache.solr.common.SolrInputDocument;
import org.apache.beam.sdk.PipelineResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.log4j.BasicConfigurator;


/**
 * A Dataflow job that reads the BigTable, converts each row into SolrInputDocument,
 * partitions the SolrInputDocs and inserts batches of SolrInputDocs into relevant Solr Cluster.
 *
 * <p>
 * Example: Assuming you have three Solr Clusters as destination: $SOLR_CLUSTER_ONE, $SOLR_CLUSTER_TWO, $SOLR_CLUSTER_THREE
 * and have created $BIGTABLE_INSTANCEID and $BIGTABLE_TABLEID, you can run the following command to start the Dataflow Job.
 * </p>
 *
 * <pre>
    java -cp target/fullindex-1.0-SNAPSHOT.jar com.google.bt.BigTableToSolrPartition \
        --project=$PROJECT \
        --region=$REGION \
        --runner=DataflowRunner \
        --gcpTempLocation=gs://$TEMP_LOCATION \
        --bigtableProjectId=$PROJECT \
        --bigtableInstanceId=$BIGTABLE_INSTANCEID \
        --bigtableTableId=$BIGTABLE_TABLEID \
        --solrCollections=$SOLR_CLUSTER_ONE
 * </pre>
 *
 * You will also need to specify the solr cluster connection in config.properties file located outside of src file.
 * Eg: solrConnection=cluster-m-0:2181,cluster-m-1:2181,cluster-m-2:2181/solr
 *
 */
public class BigTableToSolrPartition {

    private static final Logger LOG = LoggerFactory.getLogger(BigTableToSolrPartition.class);

    /**
    * An options interface to allow inputs for running your pipeline.
    */
    public interface BigtableOptions extends DataflowPipelineOptions {
        @Description("The Bigtable project ID, this can be different than your Dataflow project")
        @Required
        @Default.String("bigtable-project")
        ValueProvider<String> getBigtableProjectId();

        void setBigtableProjectId(ValueProvider<String> bigtableProjectId);

        @Description("The Bigtable instance ID")
        @Required
        @Default.String("bigtable-instance")
        ValueProvider<String> getBigtableInstanceId();

        void setBigtableInstanceId(ValueProvider<String> bigtableInstanceId);

        @Description("The Bigtable table ID in the instance.")
        @Required
        @Default.String("bigtable-table")
        ValueProvider<String> getBigtableTableId();

        void setBigtableTableId(ValueProvider<String> bigtableTableId);

        @Description("Solr Collection ID")
        @Required
        @Default.String("solr-collection-id")
        ValueProvider<String> getSolrCollectionId();

        void setSolrCollectionId(ValueProvider<String> solrCollectionId);

    }


    /**
     * The run function apply a read transform to the result of a BigtableIO.read operation.
     *
     * The function takes in two arguments: BigtableOptions and sorlConnection.
     * solrConnection is the connection config to each solr cluster.
     * You can define it in config.properties file located outside of src file.
     *
     * The read transform returns a PCollection of HBase Result objects, where each
     * element in the PCollection represents a single row in the table.
     *
     * The resulting PCollection is then inserted into a Solr Cloud cluster.
     */
    public static PipelineResult run(BigtableOptions options,  String solrConnection) {

        Pipeline p = Pipeline.create(options);

        BigtableIO.Read read = BigtableIO.read()
          .withProjectId(options.getBigtableProjectId())
          .withInstanceId(options.getBigtableInstanceId())
          .withTableId(options.getBigtableTableId());

        PCollection<SolrInputDocument> input = p.apply("Read from Bigtable", read)
        .apply("Transform to Solr Doc", MapElements.via(new BigtableToSolrInputDoc()));

        // fetching the user input solr cluster ids and connections
        String solrId = options.getSolrCollectionId().get();

        input.apply("Write to Solr Cluster",SolrIO.write().to(solrId).withConnectionConfiguration(SolrIO.ConnectionConfiguration.create(solrConnection)));

         LOG.info("Starting the pipeline");
         return p.run();

    }

    /** Translates Bigtable {@link Row} to SolrInputDocument */
    static class BigtableToSolrInputDoc extends SimpleFunction<Row, SolrInputDocument> {
        @Override
        public SolrInputDocument apply(Row row) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", row.getKey().toStringUtf8());
        for (Family family : row.getFamiliesList()) {
            String familyName = family.getName();
            for (Column column : family.getColumnsList()) {
            for (Cell cell : column.getCellsList()) {
                    doc.addField(column.getQualifier().toStringUtf8(), cell.getValue().toStringUtf8());
            }
            }
        }
        LOG.info("Solr Doc" + doc);
        return doc;
        }
    }

    // main class
    public static void main(String[] args) {

        // get properties file
        Properties prop = new Properties();
        try {

            prop.load(new FileInputStream("config.properties"));

            // get solrConnection
            String solrConnection = prop.getProperty("solrConnection");

            BigtableOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(BigtableOptions.class);

            PipelineResult result = run(options, solrConnection);

             // Wait for pipeline to finish only if it is not constructing a template.
            if (options.as(DataflowPipelineOptions.class).getTemplateLocation() == null) {
                result.waitUntilFinish();
                LOG.info("Pipeline finished with state: {} ", result.getState());
            }

        }
        catch (IOException ex)  {
            System.out.println(ex);
        }


    }

}


