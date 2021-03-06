// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.server.schema;

import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.sql.SQLException;
import java.sql.Statement;

public class Schema_94 extends SchemaVersion {
  @Inject
  Schema_94(Provider<Schema_93> prior) {
    super(prior);
  }

  @Override
  protected void migrateData(ReviewDb db, UpdateUI ui) throws SQLException {
    try (Statement stmt = newStatement(db)) {
      stmt.execute("CREATE INDEX patch_sets_byRevision ON patch_sets (revision)");
    }
  }
}
