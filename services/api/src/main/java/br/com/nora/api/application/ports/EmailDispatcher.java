package br.com.nora.api.application.ports;

/**
 * Port for handing a transactional e-mail to a worker instead of sending it inline.
 *
 * <p>Delivery goes over the provider's HTTPS API and costs hundreds of milliseconds, which makes it
 * the dominant part of any request that performs one. On the account endpoints — signup,
 * verification resend, password reset request — only some branches have a message to send, so with
 * the call on the request thread the time to respond depended on the state of the address. Handing
 * the send to a worker keeps that cost out of the response for every branch alike.
 *
 * <p>The adapter runs the task after the current transaction commits: nothing is announced for work
 * that was rolled back.
 */
public interface EmailDispatcher {

    /**
     * Runs {@code send} once the current transaction commits, off the calling thread. With no
     * transaction in progress the task is handed to the worker straight away.
     */
    void dispatchAfterCommit(Runnable send);
}
