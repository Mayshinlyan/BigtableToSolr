# BigtableToSolr
Inserting rows from Bigtable to Solr for Full Index Search 

## The solution uses the following Google Cloud services: 

- Cloud Bigtable to store the web pages metadata that we will be indexing 
- Cloud Dataflow to transform the metadata into SolrInputDocument and to insert the data into Solr Cloud. 
- Cloud Dataproc to host Solr Cloud 

## Before you begin: 

- Set up [Bigtable IAM Permissions](https://cloud.google.com/bigtable/docs/quickstart-cbt) 
- Enable Dataflow API 
- Install [Google Cloud SDK](https://cloud.google.com/sdk/docs/install) if you haven’t already 
- Authenticate using application default mode as shown below. This is recommended because it doesn’t require the generation of a json key file which exposes security risk because the file can be lost or misplaced. You can learn more about this [here](https://cloud.google.com/sdk/gcloud/reference/auth/application-default/login).   
 
  `gcloud auth application-default login`

## Create Bigtable instance: 

1. Create Bigtable Instance via [cloud console](https://cloud.google.com/bigtable/docs/quickstart-cbt#create-instance) or [command prompt](https://cloud.google.com/bigtable/docs/creating-instance#gcloud)
2. Create table [instructions link](https://cloud.google.com/bigtable/docs/quickstart-cbt)
   - `echo "project = <project-id>" > ~/.cbtrc`
   - `echo "instance = <instance-name>" > ~/.cbtrc` 
   - `cbt createtable <table-name>`
3. Create column family name. Make sure to set it as “csv”
   - `cbt createfamily <table-name> csv`
  
## Add sample data into Bigtable
 1. Clone this [git repository](https://github.com/GoogleCloudPlatform/cloud-bigtable-examples/blob/master/java/dataflow-connector-examples/src/main/java/com/google/cloud/bigtable/dataflow/example/CsvImport.java) containing CVSImport.java file,
 2. Copy the Wikipedia Kaggle dataset from [this repository](https://github.com/Mayshinlyan/BigtableToSolr/blob/main/Wiki_dataset.csv) and upload it to your Google Cloud Storage. 
 3. Navigate to the POM file and run the Dataflow insertion job. Replace ProjectId, BigtableInstanceId, InputFilePath and TableName. 

  ``` 
  mvn package exec:exec -DCsvImport -Dbigtable.projectID=<ProjectId> \
  -Dbigtable.instanceID=<BigtableInstanceId> \
  -DinputFile=gs://<InputFilePath> \
  -Dbigtable.table=<TableName> \
  -Dheaders="Id, Release_Year, Title, Origin/Ethnicity, Genre, Wiki_Page, Plot"
  ```
  
 4. Confirm the data is successfully inserted by running: 
   `cbt read <BigtableTableIdK> count=10`

## Create Solr Cluster: 

1. Create Solr Cluster on Dataproc 
   - Create the dataproc cluster using the command below: 
     ```
     gcloud dataproc clusters create <cluster-name> \
      --region=<region-name> \
      --optional-components=SOLR \
      --enable-component-gateway \
      --num-masters=3
      ... other flags
      ```
**Note**: It is important to add num-masters=3 so that Dataproc will be created in high availability mode with Zookeeper preinstalled. The Solr will then run with Zookeeper. Otherwise, there won’t be a Zookeeper port to run the Solr in SolrCloud mode. By default, the Dataproc cluster will provision 2 workers node which are necessary to run some underlying services. 

2. Create Solr Core 
    - SSH into one of the the master worker
    - `cd into /usr/lib/solr/bin`
    - `sudo -u solr ./solr create -c core-name`
    - Navigate to Solr Web UI via Web Interfaces
    - Select the core in the core selector 

3. Setup Dataflow temp location bucket 
    - `gsutil mb gs://bigtable-to-solr2/`
    - Create a folder named “temp” inside this bucket 
    - The Dataflow service account should be automatically enabled when you turn on the Dataflow API. To learn more about the Dataflow service account, check out [here](https://cloud.google.com/dataflow/docs/concepts/security-and-permissions#service_account). 


## Run the Dataflow Job: 

1. Specify the SolrConnection to your Solr Cluster in config.properties. 

   - `eg: solrConnection=cluster-m-0:2181,cluster-m-1:2181,cluster-m-2:2181/solr`

2. Run the BigTableToSolrPartition Pipeline to insert the data from BigTable to Solr 

   - `mvn clean compile package -DskipTests`
```
  java -cp target/fullindex-1.0-SNAPSHOT.jar com.google.cloud.pso.dataflow.BigTableToSolrPartition \
  --project=<project-id> \
  --region=<region-name> \
  --runner=DataflowRunner \
  --bigtableProjectId=project-id  \
  --bigtableInstanceId=instance-name \
  --bigtableTableId=table-name \
  --solrCollections=<cluster1,cluster2,cluster3>
```
3. To start searching your documents on SolrCloud UI, commit them by running this url: 
	- `<SolrUI URL>solr/<cluser-name>/update?commit=true`

eg: `https://ldmmvtdd6verrcasd62os6nl62e-dot-us-west1.dataproc.googleusercontent.com/solr/webpages/update?commit=true`
 
4. After committing, your data in Bigtable should now be indexed in the Solr search engine for full index search. You can query these data in the Solr UI.




