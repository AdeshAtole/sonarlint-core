/*
 * SonarLint Core - Implementation
 * Copyright (C) 2016-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core.container.connected;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonarsource.sonarlint.core.client.api.exceptions.StorageException;
import org.sonarsource.sonarlint.core.proto.Sonarlint.ServerIssue;
import org.sonarsource.sonarlint.core.proto.Sonarlint.ServerIssue.Location;
import org.sonarsource.sonarlint.core.proto.Sonarlint.ServerIssue.TextRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class ServerIssueStoreTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public ExpectedException exception = ExpectedException.none();

  private final String path1 = "path1";
  private final String path2 = "path2";

  private Path root;
  private ServerIssueStore store;

  @Before
  public void start() throws IOException {
    root = temporaryFolder.newFolder().toPath();
    store = new ServerIssueStore(root);
  }

  @Test
  public void should_read_object_written() {
    ServerIssue.Builder builder = ServerIssue.newBuilder();
    List<ServerIssue> issueList = new ArrayList<>();

    issueList.add(builder.setPrimaryLocation(Location.newBuilder().setPath(path1)).build());
    builder.clear();
    issueList.add(builder.setPrimaryLocation(Location.newBuilder().setPath(path2)).build());

    store.save(issueList);

    assertThat(store.load(path1)).containsExactly(issueList.get(0));
    assertThat(store.load(path2)).containsExactly(issueList.get(1));
    assertThat(store.load("nonexistent")).isEmpty();
  }

  @Test
  public void should_read_object_replaced() {
    ServerIssue issue1 = ServerIssue.newBuilder().setPrimaryLocation(Location.newBuilder().setPath(path1).setTextRange(TextRange.newBuilder().setStartLine(11))).build();
    ServerIssue issue2 = ServerIssue.newBuilder().setPrimaryLocation(Location.newBuilder().setPath(path1).setTextRange(TextRange.newBuilder().setStartLine(22))).build();

    store.save(Collections.singletonList(issue1));
    assertThat(store.load(path1)).containsOnly(issue1);

    store.save(Collections.singletonList(issue2));
    assertThat(store.load(path1)).containsOnly(issue2);
  }

  @Test
  public void should_fail_to_save_object_if_cannot_write_to_filesystem() throws IOException {
    // the sha1sum of path
    String sha1sum = "074aeb9c5551d3b52d26cf3d6568599adbff99f1";

    // Create a directory at the path where ServerIssueStore would want to create a file,
    // in order to obstruct the file creation
    Path wouldBeFile = root.resolve("0").resolve("7").resolve(sha1sum);
    if (!wouldBeFile.toFile().mkdirs()) {
      fail("could not create dummy directory");
    }

    exception.expect(StorageException.class);
    store.save(Collections.singletonList(ServerIssue.newBuilder().setPrimaryLocation(Location.newBuilder().setPath(path1)).build()));
  }

  @Test
  public void should_fail_to_delete_object() {
    String fileKey = "module1:path1";
    // the sha1sum of fileKey
    String sha1sum = "18054aada7bd3b7ddd6de55caf50ae7bee376430";

    // Create a dummy sub-directory at the path where ServerIssueStore would want to delete a file,
    // in order to obstruct the file deletion
    Path dummySubDir = root.resolve("1").resolve("8").resolve(sha1sum).resolve("dummysub");
    if (!dummySubDir.toFile().mkdirs()) {
      fail("could not create dummy sub-directory");
    }
    exception.expect(StorageException.class);
    store.delete(fileKey);
  }

  @Test
  public void should_delete_entries() {
    ServerIssue.Builder builder = ServerIssue.newBuilder();
    List<ServerIssue> issueList = new ArrayList<>();

    issueList.add(builder.setPrimaryLocation(Location.newBuilder().setPath(path1)).build());
    issueList.add(builder.setPrimaryLocation(Location.newBuilder().setPath(path2)).build());

    store.save(issueList);
    store.delete(path1);
    store.delete(path2);
    store.delete("non_existing");

    assertThat(store.load(path1)).isEmpty();
    assertThat(store.load(path2)).isEmpty();
  }
}
