/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.hadoop.pig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.*;


import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.hadoop.*;
import org.apache.cassandra.hadoop.cql3.CQLConfigHelper;
import org.apache.cassandra.thrift.*;
import org.apache.hadoop.mapreduce.*;
import org.apache.pig.Expression;
import org.apache.pig.ResourceSchema;
import org.apache.pig.ResourceSchema.ResourceFieldSchema;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.data.*;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A LoadStoreFunc for retrieving data from and storing data to Cassandra
 *
 * A row from a standard CF will be returned as nested tuples: 
 * (((key1, value1), (key2, value2)), ((name1, val1), (name2, val2))).
 */
public class CQL3Storage extends AbstractCassandraStorage
{
    private static final Logger logger = LoggerFactory.getLogger(CassandraStorage.class);

    private RecordReader<List<IColumn>, Map<ByteBuffer, IColumn>> reader;
    private RecordWriter<ByteBuffer, List<List<ByteBuffer>>> writer;

    private int pageSize = 1000;
    private String columns;
    private String outputQuery;
    private String whereClause;

    public CQL3Storage()
    {
        this(1000);
    }

    /** @param limit number of CQL rows to fetch in a thrift request */
    public CQL3Storage(int pageSize)
    {
        super();
        this.pageSize = pageSize;
        DEFAULT_INPUT_FORMAT = "org.apache.cassandra.hadoop.cql3.ColumnFamilyInputFormat";
        DEFAULT_OUTPUT_FORMAT = "org.apache.cassandra.hadoop.cql3.ColumnFamilyOutputFormat";
    }   

    public void prepareToRead(RecordReader reader, PigSplit split)
    {
        this.reader = reader;
    }

    /** get next row */
    public Tuple getNext() throws IOException
    {
        try
        {
            // load the next pair
            if (!reader.nextKeyValue())
                return null;

            CfDef cfDef = getCfDef(loadSignature);
            List<IColumn> key = reader.getCurrentKey();
            Map<ByteBuffer, IColumn> cf = reader.getCurrentValue();
            assert key != null && cf != null;

            // add key columns to the map
            for (IColumn column : key)
                cf.put(column.name(), column);

            Tuple tuple = TupleFactory.getInstance().newTuple(cfDef.column_metadata.size());
            Iterator<ColumnDef> itera = cfDef.column_metadata.iterator();
            int i = 0;
            while (itera.hasNext())
            {
                ColumnDef cdef = itera.next();
                if (cf.containsKey(cdef.name))
                    tuple.set(i, columnToTuple(cf.get(cdef.name), cfDef, UTF8Type.instance));
                else
                    tuple.set(i, TupleFactory.getInstance().newTuple());
                i++;
            }
            return tuple;
        }
        catch (InterruptedException e)
        {
            throw new IOException(e.getMessage());
        }
    }

    /** set read configuration settings */
    public void setLocation(String location, Job job) throws IOException
    {
        conf = job.getConfiguration();
        setLocationFromUri(location);

        if (username != null && password != null)
            ConfigHelper.setInputKeyspaceUserNameAndPassword(conf, username, password);
        if (splitSize > 0)
            ConfigHelper.setInputSplitSize(conf, splitSize);

        ConfigHelper.setInputColumnFamily(conf, keyspace, column_family);
        setConnectionInformation();

        CQLConfigHelper.setInputCQLPageRowSize(conf, String.valueOf(pageSize));
        if (columns != null && !columns.trim().isEmpty())
            CQLConfigHelper.setInputColumns(conf, columns);        
        if (whereClause != null && !whereClause.trim().isEmpty())
            CQLConfigHelper.setInputWhereClauses(conf, whereClause);

        if (ConfigHelper.getInputRpcPort(conf) == 0)
            throw new IOException("PIG_INPUT_RPC_PORT or PIG_RPC_PORT environment variable not set");
        if (ConfigHelper.getInputInitialAddress(conf) == null)
            throw new IOException("PIG_INPUT_INITIAL_ADDRESS or PIG_INITIAL_ADDRESS environment variable not set");
        if (ConfigHelper.getInputPartitioner(conf) == null)
            throw new IOException("PIG_INPUT_PARTITIONER or PIG_PARTITIONER environment variable not set");
        if (loadSignature == null)
            loadSignature = location;

        initSchema(loadSignature);
    }

    /** set store configuration settings */
    public void setStoreLocation(String location, Job job) throws IOException
    {
        conf = job.getConfiguration();
        setLocationFromUri(location);

        if (username != null && password != null)
            ConfigHelper.setOutputKeyspaceUserNameAndPassword(conf, username, password);
        if (splitSize > 0)
            ConfigHelper.setInputSplitSize(conf, splitSize);

        ConfigHelper.setOutputColumnFamily(conf, keyspace, column_family);
        CQLConfigHelper.setOutputPreparedStatement(conf, outputQuery);

        setConnectionInformation();

        if (ConfigHelper.getOutputRpcPort(conf) == 0)
            throw new IOException("PIG_OUTPUT_RPC_PORT or PIG_RPC_PORT environment variable not set");
        if (ConfigHelper.getOutputInitialAddress(conf) == null)
            throw new IOException("PIG_OUTPUT_INITIAL_ADDRESS or PIG_INITIAL_ADDRESS environment variable not set");
        if (ConfigHelper.getOutputPartitioner(conf) == null)
            throw new IOException("PIG_OUTPUT_PARTITIONER or PIG_PARTITIONER environment variable not set");

        initSchema(storeSignature);
    }
    
    /** schema: ((name, value), (name, value), (name, value)) where keys are in the front. */
    public ResourceSchema getSchema(String location, Job job) throws IOException
    {
        setLocation(location, job);
        CfDef cfDef = getCfDef(loadSignature);

        // top-level schema, no type
        ResourceSchema schema = new ResourceSchema();

        // get default marshallers and validators
        Map<MarshallerType, AbstractType> marshallers = getDefaultMarshallers(cfDef);
        Map<ByteBuffer, AbstractType> validators = getValidatorMap(cfDef);

        // will contain all fields for this schema
        List<ResourceFieldSchema> allSchemaFields = new ArrayList<ResourceFieldSchema>();

        // defined validators/indexes
        for (ColumnDef cdef : cfDef.column_metadata)
        {
            // make a new tuple for each col/val pair
            ResourceSchema innerTupleSchema = new ResourceSchema();
            ResourceFieldSchema innerTupleField = new ResourceFieldSchema();
            innerTupleField.setType(DataType.TUPLE);
            innerTupleField.setSchema(innerTupleSchema);
            innerTupleField.setName(new String(cdef.getName()));            
            ResourceFieldSchema idxColSchema = new ResourceFieldSchema();
            idxColSchema.setName("name");
            idxColSchema.setType(getPigType(UTF8Type.instance));

            ResourceFieldSchema valSchema = new ResourceFieldSchema();
            AbstractType validator = validators.get(cdef.name);
            if (validator == null)
                validator = marshallers.get(MarshallerType.DEFAULT_VALIDATOR);
            valSchema.setName("value");
            valSchema.setType(getPigType(validator));

            innerTupleSchema.setFields(new ResourceFieldSchema[] { idxColSchema, valSchema });
            allSchemaFields.add(innerTupleField);
        }

        // top level schema contains everything
        schema.setFields(allSchemaFields.toArray(new ResourceFieldSchema[allSchemaFields.size()]));
        return schema;
    }


    /** We use CQL3 where clause to define the partition, so do nothing here*/
    public String[] getPartitionKeys(String location, Job job)
    {
        return null;
    }

    /** We use CQL3 where clause to define the partition, so do nothing here*/
    public void setPartitionFilter(Expression partitionFilter)
    {
    }
    
    public void prepareToWrite(RecordWriter writer)
    {
        this.writer = writer;
    }

    /** output: (key, (value ... value), (value...value)) */
    public void putNext(Tuple t) throws IOException
    {
        if (t.size() < 1)
        {
            // simply nothing here, we can't even delete without a key
            logger.warn("Empty output skipped, filter empty tuples to suppress this warning");
            return;
        }
        ByteBuffer key = objToBB(t.get(0));
        if (t.getType(1) == DataType.TUPLE)
            cqlQueryFromTuple(key, t, 1);
        else
            throw new IOException("Second argument in output must be a tuple");
    }

    /** send CQL query request using data from tuple */
    private void cqlQueryFromTuple(ByteBuffer key, Tuple t, int offset) throws IOException
    {
        List<List<ByteBuffer>> bindedVariables = new ArrayList<List<ByteBuffer>>();
        for (int i = offset; i < t.size(); i++)
        {
            if (t.getType(i) == DataType.TUPLE)
            {
                Tuple inner = (Tuple) t.get(i);
                if (inner.size() > 0) // may be empty, for an indexed column that wasn't present
                    bindedVariables.add(bindedVariablesFromTuple(inner));
            }
            else
            {
                throw new IOException("Output type was not a tuple");
            }
        }
        if (bindedVariables.size() > 0)
            sendCqlQuery(key, bindedVariables);
    }

    /** compose a list of binded variables */
    private List<ByteBuffer> bindedVariablesFromTuple(Tuple t) throws IOException
    {
        List<ByteBuffer> variables = new ArrayList<ByteBuffer>();
        for (int i=0; i<t.size(); i++)
            variables.add(objToBB(t.get(i)));
        return variables;
    }

    /** writer write the data by executing CQL query */
    private void sendCqlQuery(ByteBuffer key, List<List<ByteBuffer>> bindedVariables) throws IOException
    {
        try
        {
            writer.write(key, bindedVariables);
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    /** include key columns */
    protected List<ColumnDef> getColumnMetadata(Cassandra.Client client, boolean cql3Table)
            throws InvalidRequestException,
            UnavailableException,
            TimedOutException,
            SchemaDisagreementException,
            TException,
            CharacterCodingException
    {
        List<ColumnDef> keyColumns = null;
        // get key columns
        try
        {
            keyColumns = getKeysMeta(client);
        }
        catch(IOException e)
        {
            logger.error("Error in retrieving key columns" , e);   
        }

        // get other columns
        List<ColumnDef> columns = getColumnMeta(client);

        // combine all columns in a list
        if (keyColumns != null && columns != null)
            keyColumns.addAll(columns);

        return keyColumns;
    }
    
    /** cassandra://[username:password@]<keyspace>/<columnfamily>[?[page_size=<size>]
     * [&columns=<col1,col2>][&output_query=<prepared_statement>][&where_clause=<clause>][&split_size=<size>]] */
    private void setLocationFromUri(String location) throws IOException
    {
        try
        {
            if (!location.startsWith("cassandra://"))
                throw new Exception("Bad scheme: " + location);

            String[] urlParts = location.split("\\?");
            if (urlParts.length > 1)
            {
                Map<String, String> urlQuery = getQueryMap(urlParts[1]);

                // each page row size
                if (urlQuery.containsKey("page_size"))
                    pageSize = Integer.parseInt(urlQuery.get("page_size"));

                // input query select columns
                if (urlQuery.containsKey("columns"))
                    columns = urlQuery.get("columns");

                // output prepared statement
                if (urlQuery.containsKey("output_query"))
                    outputQuery = urlQuery.get("output_query");

                // user defined where clause
                if (urlQuery.containsKey("where_clause"))
                    whereClause = urlQuery.get("where_clause");

                //split size
                if (urlQuery.containsKey("split_size"))
                    splitSize = Integer.parseInt(urlQuery.get("split_size"));
            }
            String[] parts = urlParts[0].split("/+");
            String[] credentialsAndKeyspace = parts[1].split("@");
            if (credentialsAndKeyspace.length > 1)
            {
                String[] credentials = credentialsAndKeyspace[0].split(":");
                username = credentials[0];
                password = credentials[1];
                keyspace = credentialsAndKeyspace[1];
            }
            else
            {
                keyspace = parts[1];
            }
            column_family = parts[2];
        }
        catch (Exception e)
        {
            throw new IOException("Expected 'cassandra://[username:password@]<keyspace>/<columnfamily>" +
            		                         "[?[page_size=<size>][&columns=<col1,col2>][&output_query=<prepared_statement>]" +
            		                         "[&where_clause=<clause>][&split_size=<size>]]': " + e.getMessage());
        }
    }
}

