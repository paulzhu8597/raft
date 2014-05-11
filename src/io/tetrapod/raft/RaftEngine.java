package io.tetrapod.raft;

import io.tetrapod.raft.RaftRPC.AppendEntriesResponse;
import io.tetrapod.raft.RaftRPC.AppendEntriesResponseHandler;
import io.tetrapod.raft.RaftRPC.RequestVoteResponse;

import java.io.File;
import java.security.SecureRandom;
import java.util.*;

import org.slf4j.*;

public class RaftEngine<T extends StateMachine<T>> implements RaftRPC.Requests {

   public static final Logger logger                         = LoggerFactory.getLogger(RaftEngine.class);

   public static final int    ELECTION_TIMEOUT_FIXED_MILLIS  = 1000;
   public static final int    ELECTION_TIMEOUT_RANDOM_MILLIS = 2000;
   public static final int    HEARTBEAT_MILLIS               = 250;
   public static final int    MAX_ENTRIES_PER_REQUEST        = 250;

   /**
    * These are the major raft roles we can be in
    */
   public enum Role {
      Joining, Observer, Follower, Candidate, Leader, Leaving
   }

   public volatile boolean          DEBUG  = false;

   public final SecureRandom        random = new SecureRandom();
   private final Map<Integer, Peer> peers  = new HashMap<Integer, Peer>();
   private final Log<T>             log;
   private final RaftRPC            rpc;

   /**
    * The state machine we are coordinating via raft
    */
   private final T                  stateMachine;
   private final String             clusterName;

   private Role                     role   = Role.Joining;
   private int                      myPeerId;
   private long                     currentTerm;
   private int                      votedFor;
   private int                      leaderId;
   private long                     electionTimeout;

   public class Peer {
      private final int peerId;
      private long      lastAppendMillis;
      private long      nextIndex = 1;
      private long      matchIndex;
      private boolean   appendPending;

      public Peer(int peerId) {
         this.peerId = peerId;
      }
   }

   public class Value<X> {
      public X val;

      public Value(X val) {
         this.val = val;
      }
   }

   public RaftEngine(File logDir, String clusterName, T stateMachine, RaftRPC rpc) {
      this.log = new Log<T>(logDir);
      this.rpc = rpc;
      this.stateMachine = stateMachine;
      this.clusterName = clusterName;
   }

   public synchronized void start() {
      assert (myPeerId != 0);
      role = Role.Follower; // hack
      scheduleElection();
      launchPeriodicTasksThread();
   }

   public synchronized void stop() {
      role = Role.Leaving;
   }

   @Override
   public synchronized String toString() {
      return String.format("Raft[%d] %s", myPeerId, role);
   }

   public String getClusterName() {
      return clusterName;
   }

   public T getStateMachine() {
      return stateMachine;
   }

   public synchronized void setPeerId(int peerId) {
      this.myPeerId = peerId;
   }

   public int getPeerId() {
      return myPeerId;
   }

   public synchronized Role getRole() {
      return role;
   }

   public synchronized long getCurrentTerm() {
      return currentTerm;
   }

   public Log<T> getLog() {
      return log;
   }

   public synchronized void addPeer(int peerId) {
      peers.put(peerId, new Peer(peerId));
   }

   private synchronized void scheduleElection() {
      this.electionTimeout = System.currentTimeMillis() + ELECTION_TIMEOUT_FIXED_MILLIS + random.nextInt(ELECTION_TIMEOUT_RANDOM_MILLIS);
   }

   private void launchPeriodicTasksThread() {
      final Thread t = new Thread(new Runnable() {
         public void run() {
            while (getRole() != Role.Leaving) {
               try {
                  runPeriodicTasks();
                  Thread.sleep(10);
               } catch (Throwable t) {
                  logger.error(t.getMessage(), t);
               }
            }
         }
      }, "RaftEngine");
      t.start();
   }

   private void updateStateMachine(long index) {
      while (index > stateMachine.getIndex()) {
         final Entry<T> e = log.getEntry(stateMachine.getIndex() + 1);
         e.command.applyTo(stateMachine);
         stateMachine.apply(e.index, e.term);
      }
   }

   /**
    * Called periodically to do recurring work
    */
   private synchronized void runPeriodicTasks() {
      updateStateMachine(log.getCommitIndex());

      switch (role) {
         case Joining:
            break;
         case Observer:
            break;
         case Follower:
         case Candidate:
            if (System.currentTimeMillis() > electionTimeout) {
               callElection();
            }
            break;
         case Leader:
            updateCommitIndex();
            updatePeers();
            break;
         case Leaving:
            break;
      }
   }

   private synchronized boolean isCommittable(long index) {
      int count = 1;
      int needed = 1 + (peers.size() / 2);
      for (Peer p : peers.values()) {
         if (p.matchIndex >= index) {
            count++;
            if (count >= needed)
               return true;
         }
      }
      return false;
   }

   private synchronized void updateCommitIndex() {
      assert (role == Role.Leader);
      // we can commit any entry a majority of peers have replicated
      long index = log.getLastIndex();
      for (Peer peer : peers.values()) {
         index = Math.min(index, peer.matchIndex);
      }
      index = Math.max(index, log.getCommitIndex());
      while (index <= log.getLastIndex() && isCommittable(index)) {
         log.setCommitIndex(index);
         index++;
      }
   }

   private synchronized void callElection() {
      final int votesNeeded = 1 + (peers.size() / 2);
      final Value<Integer> votes = new Value<>(1);
      role = Role.Candidate;
      ++currentTerm;
      votedFor = myPeerId;
      logger.info("{} is calling an election (term {})", this, currentTerm);
      for (Peer peer : peers.values()) {
         peer.nextIndex = 1;
         peer.matchIndex = 0;
         rpc.sendRequestVote(peer.peerId, currentTerm, myPeerId, log.getLastIndex(), log.getLastTerm(),
               new RaftRPC.RequestVoteResponseHandler() {
                  @Override
                  public void handleResponse(long term, boolean voteGranted) {
                     synchronized (RaftEngine.this) {
                        if (!stepDown(term)) {
                           if (term == currentTerm && role == Role.Candidate) {
                              if (voteGranted) {
                                 votes.val++;
                              }
                              if (votes.val >= votesNeeded) {
                                 becomeLeader();
                              }
                           }
                        }
                     }
                  }
               });
      }
      scheduleElection();
   }

   @Override
   public synchronized RequestVoteResponse handleRequestVote(long term, int candidateId, long lastLogIndex, long lastLogTerm) {
      if (term > currentTerm) {
         stepDown(term);
      }
      if (term >= currentTerm && (votedFor == 0 || votedFor == candidateId) && lastLogIndex >= log.getLastIndex()
            && lastLogTerm >= log.getLastTerm()) {
         votedFor = candidateId;
         scheduleElection();

         logger.info(String.format("%s I'm voting for %d (term %d) %d >= %d", this, candidateId, currentTerm, lastLogIndex,
               log.getLastIndex()));
         return new RequestVoteResponse(currentTerm, true);
      }
      return new RequestVoteResponse(currentTerm, false);
   }

   private synchronized boolean stepDown(long term) {
      if (term > currentTerm) {
         currentTerm = term;
         votedFor = 0;
         if (role == Role.Candidate || role == Role.Leader) {
            logger.info("{} is stepping down (term {})", this, currentTerm);
            role = Role.Follower;
            if (stateMachine.getIndex() > log.getCommitIndex()) {
               stateMachine.reset();
               updateStateMachine(log.getCommitIndex());
            }
         }
         scheduleElection();
         return true;
      }
      return false;
   }

   private synchronized void becomeLeader() {
      logger.info("{} is becoming the leader (term {})", this, currentTerm);
      role = Role.Leader;
      leaderId = myPeerId;

      for (Peer peer : peers.values()) {
         peer.matchIndex = 0;
         peer.nextIndex = log.getLastIndex() + 1;
         peer.appendPending = false;
      }
      updatePeers();
   }

   /**
    * As leader, we need to make sure we continually keep our peers up to date, and when no entries are made, to send a heart beat so that
    * they do not call an election
    */
   private synchronized void updatePeers() {
      assert role == Role.Leader;
      for (Peer peer : peers.values()) {
         updatePeer(peer);
      }
   }

   private synchronized void updatePeer(final Peer peer) {
      if (!peer.appendPending
            && (peer.nextIndex < log.getLastIndex() || System.currentTimeMillis() > peer.lastAppendMillis + HEARTBEAT_MILLIS)) {
         peer.lastAppendMillis = System.currentTimeMillis();
         peer.appendPending = true;
         final Entry<T>[] entries = log.getEntries(peer.nextIndex, MAX_ENTRIES_PER_REQUEST);
         assert (peer.nextIndex > 0);
         long prevLogIndex = peer.nextIndex - 1;
         long prevLogTerm = log.getTerm(prevLogIndex);

         logger.trace("{} is sending append entries to {}", this, peer.peerId);
         rpc.sendAppendEntries(peer.peerId, currentTerm, myPeerId, prevLogIndex, prevLogTerm, entries, log.getCommitIndex(),
               new AppendEntriesResponseHandler() {
                  @Override
                  public void handleResponse(long term, boolean success, long lastLogIndex) {
                     synchronized (RaftEngine.this) {
                        peer.appendPending = false;
                        if (role == Role.Leader) {
                           if (!stepDown(term)) {
                              if (success) {
                                 if (entries != null) {
                                    peer.matchIndex = entries[entries.length - 1].index;
                                    peer.nextIndex = peer.matchIndex + 1;
                                 }
                                 updatePeer(peer);
                              } else {
                                 assert peer.nextIndex > 1;
                                 if (peer.nextIndex > lastLogIndex) {
                                    peer.nextIndex = lastLogIndex;
                                 } else if (peer.nextIndex > 1) {
                                    peer.nextIndex--;
                                 }
                              }
                           }
                        }
                     }
                  }
               });
      }
   }

   @SuppressWarnings("unchecked")
   @Override
   public synchronized AppendEntriesResponse handleAppendEntries(long term, int leaderId, long prevLogIndex, long prevLogTerm,
         Entry<?>[] entries, long leaderCommit) {

      if (DEBUG) {
         //     logger.debug(String.format("%s append entries from %d: from <%d:%016d>", this, leaderId, prevLogTerm, prevLogIndex));
      }

      //   logger.debug(String.format("%s append entries from %d: from <%d:%016X>", this, leaderId, prevLogTerm, prevLogIndex));
      if (term >= currentTerm) {
         if (term > currentTerm) {
            stepDown(term);
         }
         scheduleElection();
         if (this.leaderId != leaderId) {
            this.leaderId = leaderId;
            logger.info("{} my new leader is {}", this, leaderId);
            logger.debug(String.format("%s append entries from %d: from <%d:%016d>", this, leaderId, prevLogTerm, prevLogIndex));
         }

         if (log.isConsistentWith(prevLogIndex, prevLogTerm)) {
            if (entries != null) {
               for (Entry<?> e : entries) {
                  if (!log.append((Entry<T>) e)) {
                     logger.warn(String.format("%s is failing append entries from %d: %s", this, leaderId, e));
                     return new AppendEntriesResponse(currentTerm, false, log.getLastIndex());
                  }
               }
            }

            log.setCommitIndex(Math.min(leaderCommit, log.getLastIndex()));

            logger.trace("{} is fine with append entries from {}", this, leaderId);
            return new AppendEntriesResponse(currentTerm, true, log.getLastIndex());
         }
      }

      logger.trace("{} is rejecting append entries from {}", this, leaderId);
      return new AppendEntriesResponse(currentTerm, false, log.getLastIndex());
   }

   public synchronized void executeCommand(Command<T> command) {
      if (role == Role.Leader) {
         if (log.append(currentTerm, command)) {
            // We optimistically update the leader's state machine in order to support
            // other client requests validating against the optimistic state. When we 
            // lose leadership, we need to reset our state machine to the commitIndex
            updateStateMachine(log.getLastIndex());
         }
      }
   }
}