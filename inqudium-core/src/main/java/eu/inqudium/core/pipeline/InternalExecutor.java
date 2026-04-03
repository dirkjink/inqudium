package eu.inqudium.core.pipeline;

interface InternalExecutor<A, R> {
  R executeWithId(String callId, A argument);
}

