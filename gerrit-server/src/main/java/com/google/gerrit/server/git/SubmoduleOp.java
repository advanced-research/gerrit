// Copyright (C) 2011 The Android Open Source Project
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

package com.google.gerrit.server.git;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.gerrit.common.data.SubscribeSection;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.client.SubmoduleSubscription;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.BatchUpdate.Listener;
import com.google.gerrit.server.git.BatchUpdate.RepoContext;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.MergeOpRepoManager.OpenRepo;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.DeletePath;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class SubmoduleOp {

  /**
   * Only used for branches without code review changes
   */
  public class GitlinkOp extends BatchUpdate.RepoOnlyOp {
    private final Branch.NameKey branch;

    GitlinkOp(Branch.NameKey branch) {
      this.branch = branch;
    }

    @Override
    public void updateRepo(RepoContext ctx) throws Exception {
      CodeReviewCommit c = composeGitlinksCommit(branch, null);
      ctx.addRefUpdate(new ReceiveCommand(c.getParent(0), c, branch.get()));
      addBranchTip(branch, c);
    }
  }

  public interface Factory {
    SubmoduleOp create(
        Set<Branch.NameKey> updatedBranches, MergeOpRepoManager orm);
  }

  private static final Logger log = LoggerFactory.getLogger(SubmoduleOp.class);

  private final GitModules.Factory gitmodulesFactory;
  private final PersonIdent myIdent;
  private final ProjectCache projectCache;
  private final ProjectState.Factory projectStateFactory;
  private final boolean verboseSuperProject;
  private final boolean enableSuperProjectSubscriptions;
  private final Multimap<Branch.NameKey, SubmoduleSubscription> targets;
  private final Set<Branch.NameKey> updatedBranches;
  private final MergeOpRepoManager orm;
  private final Map<Branch.NameKey, CodeReviewCommit> branchTips;
  private final Map<Branch.NameKey, GitModules> branchGitModules;
  private final ImmutableSet<Branch.NameKey> sortedBranches;

  @AssistedInject
  public SubmoduleOp(
      GitModules.Factory gitmodulesFactory,
      @GerritPersonIdent PersonIdent myIdent,
      @GerritServerConfig Config cfg,
      ProjectCache projectCache,
      ProjectState.Factory projectStateFactory,
      @Assisted Set<Branch.NameKey> updatedBranches,
      @Assisted MergeOpRepoManager orm) throws SubmoduleException {
    this.gitmodulesFactory = gitmodulesFactory;
    this.myIdent = myIdent;
    this.projectCache = projectCache;
    this.projectStateFactory = projectStateFactory;
    this.verboseSuperProject = cfg.getBoolean("submodule",
        "verboseSuperprojectUpdate", true);
    this.enableSuperProjectSubscriptions = cfg.getBoolean("submodule",
        "enableSuperProjectSubscriptions", true);
    this.orm = orm;
    this.updatedBranches = updatedBranches;
    this.targets = HashMultimap.create();
    this.branchTips = new HashMap<>();
    this.branchGitModules = new HashMap<>();
    this.sortedBranches = calculateSubscriptionMap();
  }

  private ImmutableSet<Branch.NameKey> calculateSubscriptionMap()
      throws SubmoduleException {
    if (!enableSuperProjectSubscriptions) {
      logDebug("Updating superprojects disabled");
      return null;
    }

    logDebug("Calculating superprojects - submodules map");
    LinkedHashSet<Branch.NameKey> allVisited = new LinkedHashSet<>();
    for (Branch.NameKey updatedBranch : updatedBranches) {
      if (allVisited.contains(updatedBranch)) {
        continue;
      }

      searchForSuperprojects(updatedBranch, new LinkedHashSet<Branch.NameKey>(),
          allVisited);
    }

    // Since the searchForSuperprojects will add the superprojects before one
    // submodule in sortedBranches, need reverse the order of it
    reverse(allVisited);
    return ImmutableSet.copyOf(allVisited);
  }

  private void searchForSuperprojects(Branch.NameKey current,
      LinkedHashSet<Branch.NameKey> currentVisited,
      LinkedHashSet<Branch.NameKey> allVisited)
      throws SubmoduleException {
    logDebug("Now processing " + current);

    if (currentVisited.contains(current)) {
      throw new SubmoduleException(
          "Branch level circular subscriptions detected:  " +
              printCircularPath(currentVisited, current));
    }

    if (allVisited.contains(current)) {
      return;
    }

    currentVisited.add(current);
    try {
      Collection<SubmoduleSubscription> subscriptions =
          superProjectSubscriptionsForSubmoduleBranch(current);
      for (SubmoduleSubscription sub : subscriptions) {
        Branch.NameKey superProject = sub.getSuperProject();
        searchForSuperprojects(superProject, currentVisited, allVisited);
        targets.put(superProject, sub);
      }
    } catch (IOException e) {
      throw new SubmoduleException("Cannot find superprojects for " + current,
          e);
    }
    currentVisited.remove(current);
    allVisited.add(current);
  }

  private static <T> void reverse(LinkedHashSet<T> set) {
    if (set == null) {
      return;
    }

    Deque<T> q = new ArrayDeque<>(set);
    set.clear();

    while (!q.isEmpty()) {
      set.add(q.removeLast());
    }
  }

  private <T> String printCircularPath(LinkedHashSet<T> p, T target) {
    StringBuilder sb = new StringBuilder();
    sb.append(target);
    ArrayList<T> reverseP = new ArrayList<>(p);
    Collections.reverse(reverseP);
    for (T t : reverseP) {
      sb.append("->");
      sb.append(t);
      if (t.equals(target)) {
        break;
      }
    }
    return sb.toString();
  }

  private Collection<Branch.NameKey> getDestinationBranches(Branch.NameKey src,
      SubscribeSection s) throws IOException {
    Collection<Branch.NameKey> ret = new ArrayList<>();
    logDebug("Inspecting SubscribeSection " + s);
    for (RefSpec r : s.getRefSpecs()) {
      logDebug("Inspecting ref " + r);
      if (r.matchSource(src.get())) {
        if (r.getDestination() == null) {
          // no need to care for wildcard, as we matched already
          try {
            orm.openRepo(s.getProject(), false);
          } catch (NoSuchProjectException e) {
            // A project listed a non existent project to be allowed
            // to subscribe to it. Allow this for now.
            continue;
          }
          OpenRepo or = orm.getRepo(s.getProject());
          for (Ref ref : or.repo.getRefDatabase().getRefs(
              RefNames.REFS_HEADS).values()) {
            ret.add(new Branch.NameKey(s.getProject(), ref.getName()));
          }
        } else if (r.isWildcard()) {
          // refs/heads/*:refs/heads/*
          ret.add(new Branch.NameKey(s.getProject(),
              r.expandFromSource(src.get()).getDestination()));
        } else {
          // e.g. refs/heads/master:refs/heads/stable
          ret.add(new Branch.NameKey(s.getProject(), r.getDestination()));
        }
      }
    }
    logDebug("Returning possible branches: " + ret +
        "for project " + s.getProject());
    return ret;
  }

  public Collection<SubmoduleSubscription>
      superProjectSubscriptionsForSubmoduleBranch(Branch.NameKey srcBranch)
      throws IOException {
    logDebug("Calculating possible superprojects for " + srcBranch);
    Collection<SubmoduleSubscription> ret = new ArrayList<>();
    Project.NameKey srcProject = srcBranch.getParentKey();
    ProjectConfig cfg = projectCache.get(srcProject).getConfig();
    for (SubscribeSection s : projectStateFactory.create(cfg)
        .getSubscribeSections(srcBranch)) {
      logDebug("Checking subscribe section " + s);
      Collection<Branch.NameKey> branches =
          getDestinationBranches(srcBranch, s);
      for (Branch.NameKey targetBranch : branches) {
        Project.NameKey targetProject = targetBranch.getParentKey();
        try {
          orm.openRepo(targetProject, false);
          OpenRepo or = orm.getRepo(targetProject);
          ObjectId id = or.repo.resolve(targetBranch.get());
          if (id == null) {
            logDebug("The branch " + targetBranch + " doesn't exist.");
            continue;
          }
        } catch (NoSuchProjectException e) {
          logDebug("The project " + targetProject + " doesn't exist");
          continue;
        }

        GitModules m = branchGitModules.get(targetBranch);
        if (m == null) {
          m = gitmodulesFactory.create(targetBranch, orm);
          branchGitModules.put(targetBranch, m);
        }
        ret.addAll(m.subscribedTo(srcBranch));
      }
    }
    logDebug("Calculated superprojects for " + srcBranch + " are " + ret);
    return ret;
  }

  public void updateSuperProjects() throws SubmoduleException {
    ImmutableSet<Project.NameKey> projects = getProjectsInOrder();
    if (projects == null) {
      return;
    }

    SetMultimap<Project.NameKey, Branch.NameKey> dst = branchesByProject();
    LinkedHashSet<Project.NameKey> superProjects = new LinkedHashSet<>();
    try {
      for (Project.NameKey project : projects) {
        // only need superprojects
        if (dst.containsKey(project)) {
          superProjects.add(project);
          // get a new BatchUpdate for the super project
          orm.openRepo(project, false);
          //TODO:czhen remove this when MergeOp combine this into BatchUpdate
          orm.getRepo(project).resetUpdate();
          for (Branch.NameKey branch : dst.get(project)) {
            SubmoduleOp.GitlinkOp op = new SubmoduleOp.GitlinkOp(branch);
            orm.getRepo(project).getUpdate().addRepoOnlyOp(op);
          }
        }
      }
      BatchUpdate.execute(orm.batchUpdates(superProjects), Listener.NONE);
    } catch (RestApiException | UpdateException | IOException |
        NoSuchProjectException e) {
      throw new SubmoduleException("Cannot update gitlinks", e);
    }
  }

  /**
   * Create a gitlink update commit on the tip of subscriber or modify the
   * baseCommit with gitlink update patch
   */
  public CodeReviewCommit composeGitlinksCommit(
      final Branch.NameKey subscriber, RevCommit baseCommit)
      throws IOException, SubmoduleException {
    PersonIdent author = null;
    StringBuilder msgbuf = new StringBuilder("Update git submodules\n\n");
    boolean sameAuthorForAll = true;

    try {
      orm.openRepo(subscriber.getParentKey(), false);
    } catch (NoSuchProjectException | IOException e) {
      throw new SubmoduleException("Cannot access superproject", e);
    }

    OpenRepo or = orm.getRepo(subscriber.getParentKey());
    Ref r = or.repo.exactRef(subscriber.get());
    if (r == null) {
      throw new SubmoduleException(
          "The branch was probably deleted from the subscriber repository");
    }

    RevCommit currentCommit = (baseCommit != null) ? baseCommit :
        or.rw.parseCommit(or.repo.exactRef(subscriber.get()).getObjectId());
    or.rw.parseBody(currentCommit);

    DirCache dc = readTree(or.rw, currentCommit);
    DirCacheEditor ed = dc.editor();

    for (SubmoduleSubscription s : targets.get(subscriber)) {
      try {
        orm.openRepo(s.getSubmodule().getParentKey(), false);
      } catch (NoSuchProjectException | IOException e) {
        throw new SubmoduleException("Cannot access submodule", e);
      }
      OpenRepo subOr = orm.getRepo(s.getSubmodule().getParentKey());
      Repository subRepo = subOr.repo;

      Ref ref = subRepo.getRefDatabase().exactRef(s.getSubmodule().get());
      if (ref == null) {
        ed.add(new DeletePath(s.getPath()));
        continue;
      }

      ObjectId updateTo = ref.getObjectId();
      if (branchTips.containsKey(s.getSubmodule())) {
        updateTo = branchTips.get(s.getSubmodule());
      }
      RevWalk subOrRw = subOr.rw;
      final RevCommit newCommit = subOrRw.parseCommit(updateTo);

      subOrRw.parseBody(newCommit);
      if (author == null) {
        author = newCommit.getAuthorIdent();
      } else if (!author.equals(newCommit.getAuthorIdent())) {
        sameAuthorForAll = false;
      }

      DirCacheEntry dce = dc.getEntry(s.getPath());
      ObjectId oldId;
      if (dce != null) {
        if (!dce.getFileMode().equals(FileMode.GITLINK)) {
          String errMsg = "Requested to update gitlink " + s.getPath() + " in "
              + s.getSubmodule().getParentKey().get() + " but entry "
              + "doesn't have gitlink file mode.";
          throw new SubmoduleException(errMsg);
        }
        oldId = dce.getObjectId();
      } else {
        // This submodule did not exist before. We do not want to add
        // the full submodule history to the commit message, so omit it.
        oldId = updateTo;
      }

      ed.add(new PathEdit(s.getPath()) {
        @Override
        public void apply(DirCacheEntry ent) {
          ent.setFileMode(FileMode.GITLINK);
          ent.setObjectId(newCommit.getId());
        }
      });
      if (verboseSuperProject) {
        msgbuf.append("Project: " + s.getSubmodule().getParentKey().get());
        msgbuf.append(" " + s.getSubmodule().getShortName());
        msgbuf.append(" " + newCommit.getName());
        msgbuf.append("\n\n");

        try {
          subOrRw.resetRetain(subOr.canMergeFlag);
          subOrRw.markStart(newCommit);
          subOrRw.markUninteresting(subOrRw.parseCommit(oldId));
          for (RevCommit c : subOrRw) {
            subOrRw.parseBody(c);
            msgbuf.append(c.getFullMessage() + "\n\n");
          }
        } catch (IOException e) {
          throw new SubmoduleException("Could not perform a revwalk to "
              + "create superproject commit message", e);
        }
      }
    }
    ed.finish();


    ObjectInserter oi = or.ins;
    CodeReviewRevWalk rw = or.rw;
    ObjectId tree = dc.writeTree(oi);

    if (!sameAuthorForAll || author == null) {
      author = myIdent;
    }

    CommitBuilder commit = new CommitBuilder();
    commit.setTreeId(tree);
    if (baseCommit != null) {
      // modify the baseCommit
      commit.setParentIds(baseCommit.getParents());
      commit.setMessage(baseCommit.getFullMessage() + "\n\n" + msgbuf.toString());
      commit.setAuthor(baseCommit.getAuthorIdent());
    } else {
      // create a new commit
      commit.setParentId(currentCommit);
      commit.setMessage(msgbuf.toString());
      commit.setAuthor(author);
    }
    commit.setCommitter(myIdent);

    ObjectId id = oi.insert(commit);
    return rw.parseCommit(id);
  }

  private static DirCache readTree(RevWalk rw, ObjectId base)
      throws IOException {
    final DirCache dc = DirCache.newInCore();
    final DirCacheBuilder b = dc.builder();
    b.addTree(new byte[0], // no prefix path
        DirCacheEntry.STAGE_0, // standard stage
        rw.getObjectReader(), rw.parseTree(base));
    b.finish();
    return dc;
  }

  public SetMultimap<Project.NameKey, Branch.NameKey> branchesByProject() {
    SetMultimap<Project.NameKey, Branch.NameKey> ret = HashMultimap.create();
    for (Branch.NameKey branch : targets.keySet()) {
      ret.put(branch.getParentKey(), branch);
    }

    return ret;
  }

  public ImmutableSet<Project.NameKey> getProjectsInOrder()
      throws SubmoduleException {
    if (sortedBranches == null) {
      return null;
    }

    LinkedHashSet<Project.NameKey> projects = new LinkedHashSet<>();
    Project.NameKey prev = null;
    for (Branch.NameKey branch : sortedBranches) {
      Project.NameKey project = branch.getParentKey();
      if (!project.equals(prev)) {
        if (projects.contains(project)) {
          throw new SubmoduleException(
              "Project level circular subscriptions detected:  " +
                  printCircularPath(projects, project));
        } else {
          projects.add(project);
        }
      }
      prev = project;
    }

    return ImmutableSet.copyOf(projects);
  }

  public ImmutableSet<Branch.NameKey> getBranchesInOrder() {
    return sortedBranches;
  }

  public void addBranchTip(Branch.NameKey branch, CodeReviewCommit tip) {
    branchTips.put(branch, tip);
  }

  private void logDebug(String msg, Object... args) {
    if (log.isDebugEnabled()) {
      log.debug("[" + orm.getSubmissionId() + "]" + msg, args);
    }
  }
}
