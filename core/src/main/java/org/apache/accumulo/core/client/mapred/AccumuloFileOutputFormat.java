/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.core.client.mapred;

import java.io.IOException;

import org.apache.accumulo.core.client.rfile.RFile;
import org.apache.accumulo.core.client.rfile.RFileWriter;
import org.apache.accumulo.core.client.sample.SamplerConfiguration;
import org.apache.accumulo.core.clientImpl.mapreduce.lib.FileOutputConfigurator;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.Progressable;
import org.apache.log4j.Logger;

/**
 * This class allows MapReduce jobs to write output in the Accumulo data file format.<br>
 * Care should be taken to write only sorted data (sorted by {@link Key}), as this is an important
 * requirement of Accumulo data files.
 *
 * <p>
 * The output path to be created must be specified via
 * {@link AccumuloFileOutputFormat#setOutputPath(JobConf, Path)}. This is inherited from
 * {@link FileOutputFormat#setOutputPath(JobConf, Path)}. Other methods from
 * {@link FileOutputFormat} are not supported and may be ignored or cause failures. Using other
 * Hadoop configuration options that affect the behavior of the underlying files directly in the
 * Job's configuration may work, but are not directly supported at this time.
 *
 * @deprecated since 2.0.0; Use org.apache.accumulo.hadoop.mapred instead from the
 *             accumulo-hadoop-mapreduce.jar
 */
@Deprecated(since = "2.0.0")
public class AccumuloFileOutputFormat extends FileOutputFormat<Key,Value> {

  private static final Class<?> CLASS = AccumuloFileOutputFormat.class;
  protected static final Logger log = Logger.getLogger(CLASS);

  /**
   * Sets the compression type to use for data blocks. Specifying a compression may require
   * additional libraries to be available to your Job.
   *
   * @param job
   *          the Hadoop job instance to be configured
   * @param compressionType
   *          one of "none", "gz", "bzip2", "lzo", "lz4", "snappy", or "zstd"
   * @since 1.5.0
   */
  public static void setCompressionType(JobConf job, String compressionType) {
    FileOutputConfigurator.setCompressionType(CLASS, job, compressionType);
  }

  /**
   * Sets the size for data blocks within each file.<br>
   * Data blocks are a span of key/value pairs stored in the file that are compressed and indexed as
   * a group.
   *
   * <p>
   * Making this value smaller may increase seek performance, but at the cost of increasing the size
   * of the indexes (which can also affect seek performance).
   *
   * @param job
   *          the Hadoop job instance to be configured
   * @param dataBlockSize
   *          the block size, in bytes
   * @since 1.5.0
   */
  public static void setDataBlockSize(JobConf job, long dataBlockSize) {
    FileOutputConfigurator.setDataBlockSize(CLASS, job, dataBlockSize);
  }

  /**
   * Sets the size for file blocks in the file system; file blocks are managed, and replicated, by
   * the underlying file system.
   *
   * @param job
   *          the Hadoop job instance to be configured
   * @param fileBlockSize
   *          the block size, in bytes
   * @since 1.5.0
   */
  public static void setFileBlockSize(JobConf job, long fileBlockSize) {
    FileOutputConfigurator.setFileBlockSize(CLASS, job, fileBlockSize);
  }

  /**
   * Sets the size for index blocks within each file; smaller blocks means a deeper index hierarchy
   * within the file, while larger blocks mean a more shallow index hierarchy within the file. This
   * can affect the performance of queries.
   *
   * @param job
   *          the Hadoop job instance to be configured
   * @param indexBlockSize
   *          the block size, in bytes
   * @since 1.5.0
   */
  public static void setIndexBlockSize(JobConf job, long indexBlockSize) {
    FileOutputConfigurator.setIndexBlockSize(CLASS, job, indexBlockSize);
  }

  /**
   * Sets the file system replication factor for the resulting file, overriding the file system
   * default.
   *
   * @param job
   *          the Hadoop job instance to be configured
   * @param replication
   *          the number of replicas for produced files
   * @since 1.5.0
   */
  public static void setReplication(JobConf job, int replication) {
    FileOutputConfigurator.setReplication(CLASS, job, replication);
  }

  /**
   * Specify a sampler to be used when writing out data. This will result in the output file having
   * sample data.
   *
   * @param job
   *          The Hadoop job instance to be configured
   * @param samplerConfig
   *          The configuration for creating sample data in the output file.
   * @since 1.8.0
   */

  public static void setSampler(JobConf job, SamplerConfiguration samplerConfig) {
    FileOutputConfigurator.setSampler(CLASS, job, samplerConfig);
  }

  @Override
  public RecordWriter<Key,Value> getRecordWriter(FileSystem ignored, JobConf job, String name,
      Progressable progress) throws IOException {
    // get the path of the temporary output file
    final Configuration conf = job;
    final AccumuloConfiguration acuConf =
        FileOutputConfigurator.getAccumuloConfiguration(CLASS, job);

    final String extension = acuConf.get(Property.TABLE_FILE_TYPE);
    final Path file =
        new Path(getWorkOutputPath(job), getUniqueName(job, "part") + "." + extension);
    final int visCacheSize = FileOutputConfigurator.getVisibilityCacheSize(conf);

    return new RecordWriter<>() {
      RFileWriter out = null;

      @Override
      public void close(Reporter reporter) throws IOException {
        if (out != null)
          out.close();
      }

      @Override
      public void write(Key key, Value value) throws IOException {
        if (out == null) {
          out = RFile.newWriter().to(file.toString()).withFileSystem(file.getFileSystem(conf))
              .withTableProperties(acuConf).withVisibilityCacheSize(visCacheSize).build();
          out.startDefaultLocalityGroup();
        }
        out.append(key, value);
      }
    };
  }
}
