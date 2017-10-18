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

package com.google.gerrit.server.project;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.Project.NameKey;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Singleton
public class DefaultProjectNameLockManager implements ProjectNameLockManager {

  public static class Module extends AbstractModule {
    @Override
    protected void configure() {
      DynamicItem.bind(binder(), ProjectNameLockManager.class)
          .to(DefaultProjectNameLockManager.class);
    }
  }

  LoadingCache<Project.NameKey, Lock> lockCache =
      CacheBuilder.newBuilder()
          .maximumSize(1024)
          .expireAfterAccess(5, TimeUnit.MINUTES)
          .build(
              new CacheLoader<Project.NameKey, Lock>() {
                @Override
                public Lock load(NameKey key) throws Exception {
                  return new ReentrantLock();
                }
              });

  @Override
  public Lock getLock(NameKey name) {
    try {
      return lockCache.get(name);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}