// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.group.db;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.MetaDataUpdate;
import java.io.IOException;
import org.eclipse.jgit.lib.CommitBuilder;

@FunctionalInterface
public interface MetaDataUpdateFactory {
  /**
   * Create a {@link MetaDataUpdate} for the given project.
   *
   * <p>The {@link CommitBuilder} of the returned {@link MetaDataUpdate} must have author and
   * committer set.
   *
   * @param projectName The project for which meta data should be updated.
   * @return A new {@link MetaDataUpdate} instance for the given project.
   */
  MetaDataUpdate create(Project.NameKey projectName) throws IOException;
}
