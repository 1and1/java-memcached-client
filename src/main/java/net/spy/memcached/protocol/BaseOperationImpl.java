package net.spy.memcached.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;

import net.spy.memcached.MemcachedNode;
import net.spy.memcached.compat.SpyObject;
import net.spy.memcached.ops.CancelledOperationStatus;
import net.spy.memcached.ops.Operation;
import net.spy.memcached.ops.OperationCallback;
import net.spy.memcached.ops.OperationErrorType;
import net.spy.memcached.ops.OperationException;
import net.spy.memcached.ops.OperationState;
import net.spy.memcached.ops.OperationStatus;

/**
 * Base class for protocol-specific operation implementations.
 */
public abstract class BaseOperationImpl extends SpyObject implements Operation {

	/**
	 * Status object for canceled operations.
	 */
	public static final OperationStatus CANCELLED =
		new CancelledOperationStatus();
	private OperationState state = OperationState.WRITE_QUEUED;
	private ByteBuffer cmd = null;
	private boolean cancelled = false;
	private OperationException exception = null;
	protected OperationCallback callback = null;
	private volatile MemcachedNode handlingNode = null;
	private boolean timedout;
	private long creationTime;
	private boolean timedOutUnsent = false;
	protected Collection<MemcachedNode> notMyVbucketNodes = new HashSet<MemcachedNode>();

	public BaseOperationImpl() {
		super();
		creationTime = System.nanoTime();
	}

	/**
	 * Get the operation callback associated with this operation.
	 */
	public final OperationCallback getCallback() {
		return callback;
	}

	/**
	 * Set the callback for this instance.
	 */
	protected void setCallback(OperationCallback to) {
		callback=to;
	}

	public final synchronized boolean isCancelled() {
		return cancelled;
	}

	public final boolean hasErrored() {
		return exception != null;
	}

	public final OperationException getException() {
		return exception;
	}

	public final synchronized void cancel() {
		cancelled=true;
		wasCancelled();
		callback.complete();
	}

	/**
	 * This is called on each subclass whenever an operation was cancelled.
	 */
	protected void wasCancelled() {
		getLogger().debug("was cancelled.");
	}

	public final synchronized OperationState getState() {
		return state;
	}

	public final synchronized ByteBuffer getBuffer() {
		return cmd;
	}

	/**
	 * Set the write buffer for this operation.
	 */
	protected final synchronized void setBuffer(ByteBuffer to) {
		assert to != null : "Trying to set buffer to null";
		cmd=to;
		cmd.mark();
	}

	/**
	 * Transition the state of this operation to the given state.
	 */
	protected final synchronized void transitionState(OperationState newState) {
		getLogger().debug("Transitioned state from %s to %s", state, newState);
		state=newState;
		// Discard our buffer when we no longer need it.
		if(state != OperationState.WRITE_QUEUED && state != OperationState.WRITING) {
			cmd=null;
		}
		if(state == OperationState.COMPLETE) {
			callback.complete();
		}
	}

	public final void writing() {
		transitionState(OperationState.WRITING);
	}

	public final void writeComplete() {
		transitionState(OperationState.READING);
	}

	public abstract void initialize();

	public abstract void readFromBuffer(ByteBuffer data) throws IOException;

	protected void handleError(OperationErrorType eType, String line)
		throws IOException {
		getLogger().error("Error:  %s", line);
		switch(eType) {
			case GENERAL:
				exception=new OperationException();
				break;
			case SERVER:
				exception=new OperationException(eType, line);
				break;
			case CLIENT:
				exception=new OperationException(eType, line);
				break;
			default: assert false;
		}
		transitionState(OperationState.COMPLETE);
		throw exception;
	}

	public void handleRead(ByteBuffer data) {
		assert false;
	}

	public MemcachedNode getHandlingNode() {
		return handlingNode;
	}

	public void setHandlingNode(MemcachedNode to) {
		handlingNode = to;
	}

        @Override
        public synchronized void timeOut() {
            timedout = true;
            callback.complete();
        }

        @Override
        public synchronized boolean isTimedOut() {
            return timedout;
        }

	@Override
	public synchronized boolean isTimedOut(long ttlMillis) {
		long elapsed = System.nanoTime();
		long ttlNanos = ttlMillis * 1000 * 1000;
		if (elapsed - creationTime > ttlNanos) {
			timedOutUnsent = true;
			timedout = true;
			callback.complete();
		} else {
			// timedout would be false, but we cannot allow you to untimeout an operation
			if (timedout) {
				throw new IllegalArgumentException("Operation has already timed out; ttl " +
								   "specified would allow it to be valid.");
			}
		}
		return timedout;
        }

	@Override
	public boolean isTimedOutUnsent() {
		return timedOutUnsent;
	}
}
