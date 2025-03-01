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
package org.apache.accumulo.server.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.TabletFileUtil;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.CurrentLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.server.cli.ServerUtilOpts;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public class LocalityCheck {

  public int run(String[] args) throws Exception {
    ServerUtilOpts opts = new ServerUtilOpts();
    opts.parseArgs(LocalityCheck.class.getName(), args);

    Span span = TraceUtil.startSpan(LocalityCheck.class, "run");
    try (Scope scope = span.makeCurrent()) {

      VolumeManager fs = opts.getServerContext().getVolumeManager();
      try (AccumuloClient accumuloClient =
          Accumulo.newClient().from(opts.getClientProps()).build()) {
        Scanner scanner = accumuloClient.createScanner(MetadataTable.NAME, Authorizations.EMPTY);
        scanner.fetchColumnFamily(CurrentLocationColumnFamily.NAME);
        scanner.fetchColumnFamily(DataFileColumnFamily.NAME);
        scanner.setRange(TabletsSection.getRange());

        Map<String,Long> totalBlocks = new HashMap<>();
        Map<String,Long> localBlocks = new HashMap<>();
        ArrayList<String> files = new ArrayList<>();

        for (Entry<Key,Value> entry : scanner) {
          Key key = entry.getKey();
          if (key.compareColumnFamily(CurrentLocationColumnFamily.NAME) == 0) {
            String location = entry.getValue().toString();
            String[] parts = location.split(":");
            String host = parts[0];
            addBlocks(fs, host, files, totalBlocks, localBlocks);
            files.clear();
          } else if (key.compareColumnFamily(DataFileColumnFamily.NAME) == 0) {
            files.add(TabletFileUtil.validate(key.getColumnQualifierData().toString()));
          }
        }
        System.out.println(" Server         %local  total blocks");
        for (Entry<String,Long> entry : totalBlocks.entrySet()) {
          final String host = entry.getKey();
          final Long blocksForHost = entry.getValue();
          System.out.printf("%15s %5.1f %8d%n", host, localBlocks.get(host) * 100.0 / blocksForHost,
              blocksForHost);
        }
      }
      return 0;
    } finally {
      span.end();
    }
  }

  private void addBlocks(VolumeManager fs, String host, ArrayList<String> files,
      Map<String,Long> totalBlocks, Map<String,Long> localBlocks) throws Exception {
    long allBlocks = 0;
    long matchingBlocks = 0;
    if (!totalBlocks.containsKey(host)) {
      totalBlocks.put(host, 0L);
      localBlocks.put(host, 0L);
    }
    for (String file : files) {
      Path filePath = new Path(file);
      FileSystem ns = fs.getFileSystemByPath(filePath);
      FileStatus fileStatus = ns.getFileStatus(filePath);
      BlockLocation[] fileBlockLocations =
          ns.getFileBlockLocations(fileStatus, 0, fileStatus.getLen());
      for (BlockLocation blockLocation : fileBlockLocations) {
        allBlocks++;
        for (String location : blockLocation.getHosts()) {
          HostAndPort hap = HostAndPort.fromParts(location, 0);
          if (hap.getHost().equals(host)) {
            matchingBlocks++;
            break;
          }
        }
      }
    }
    totalBlocks.put(host, allBlocks + totalBlocks.get(host));
    localBlocks.put(host, matchingBlocks + localBlocks.get(host));
  }

  public static void main(String[] args) throws Exception {
    LocalityCheck check = new LocalityCheck();
    System.exit(check.run(args));
  }
}
