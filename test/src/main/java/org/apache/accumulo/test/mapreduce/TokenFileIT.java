/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test.mapreduce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.client.mapreduce.AccumuloOutputFormat;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class TokenFileIT extends AccumuloClusterHarness {
  private static AssertionError e1 = null;

  private static class MRTokenFileTester extends Configured implements Tool {
    private static class TestMapper extends Mapper<Key,Value,Text,Mutation> {
      Key key = null;
      int count = 0;

      @Override
      protected void map(Key k, Value v, Context context) throws IOException, InterruptedException {
        try {
          if (key != null)
            assertEquals(key.getRow().toString(), new String(v.get()));
          assertEquals(k.getRow(), new Text(String.format("%09x", count + 1)));
          assertEquals(new String(v.get()), String.format("%09x", count));
        } catch (AssertionError e) {
          e1 = e;
        }
        key = new Key(k);
        count++;
      }

      @Override
      protected void cleanup(Context context) throws IOException, InterruptedException {
        Mutation m = new Mutation("total");
        m.put("", "", Integer.toString(count));
        context.write(new Text(), m);
      }
    }

    @SuppressWarnings("deprecation")
    @Override
    public int run(String[] args) throws Exception {

      if (args.length != 3) {
        throw new IllegalArgumentException("Usage : " + MRTokenFileTester.class.getName()
            + " <token file> <inputtable> <outputtable>");
      }

      String user = getAdminPrincipal();
      String tokenFile = args[0];
      String table1 = args[1];
      String table2 = args[2];

      Job job = Job.getInstance(getConf(),
          this.getClass().getSimpleName() + "_" + System.currentTimeMillis());
      job.setJarByClass(this.getClass());

      job.setInputFormatClass(AccumuloInputFormat.class);

      AccumuloInputFormat.setConnectorInfo(job, user, tokenFile);
      AccumuloInputFormat.setInputTableName(job, table1);

      job.setMapperClass(TestMapper.class);
      job.setMapOutputKeyClass(Key.class);
      job.setMapOutputValueClass(Value.class);
      job.setOutputFormatClass(AccumuloOutputFormat.class);
      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(Mutation.class);

      AccumuloOutputFormat.setConnectorInfo(job, user, tokenFile);
      AccumuloOutputFormat.setCreateTables(job, false);
      AccumuloOutputFormat.setDefaultTableName(job, table2);

      job.setNumReduceTasks(0);

      job.waitForCompletion(true);

      return job.isSuccessful() ? 0 : 1;
    }

    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "path provided by test")
    public static void main(String[] args) throws Exception {
      Configuration conf = CachedConfiguration.getInstance();
      conf.set("hadoop.tmp.dir", new File(args[0]).getParent());
      conf.set("mapreduce.framework.name", "local");
      conf.set("mapreduce.cluster.local.dir",
          new File(System.getProperty("user.dir"), "target/mapreduce-tmp").getAbsolutePath());
      assertEquals(0, ToolRunner.run(conf, new MRTokenFileTester(), args));
    }
  }

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(
      new File(System.getProperty("user.dir") + "/target"));

  @Test
  public void testMR() throws Exception {
    String[] tableNames = getUniqueNames(2);
    String table1 = tableNames[0];
    String table2 = tableNames[1];
    try (AccumuloClient c = getAccumuloClient()) {
      c.tableOperations().create(table1);
      c.tableOperations().create(table2);
      BatchWriter bw = c.createBatchWriter(table1, new BatchWriterConfig());
      for (int i = 0; i < 100; i++) {
        Mutation m = new Mutation(new Text(String.format("%09x", i + 1)));
        m.put(new Text(), new Text(), new Value(String.format("%09x", i).getBytes()));
        bw.addMutation(m);
      }
      bw.close();

      File tf = folder.newFile("client.properties");
      try (PrintStream out = new PrintStream(tf)) {
        getClientInfo().getProperties().store(out, "Credentials for " + getClass().getName());
      }

      MRTokenFileTester.main(new String[] {tf.getAbsolutePath(), table1, table2});
      assertNull(e1);

      try (Scanner scanner = c.createScanner(table2, new Authorizations())) {
        Iterator<Entry<Key,Value>> iter = scanner.iterator();
        assertTrue(iter.hasNext());
        Entry<Key,Value> entry = iter.next();
        assertEquals(Integer.parseInt(new String(entry.getValue().get())), 100);
        assertFalse(iter.hasNext());
      }
    }
  }
}
