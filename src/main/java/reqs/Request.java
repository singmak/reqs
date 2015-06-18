package reqs;

import reqs.util.Debugger;

import java.util.List;

/**
 * Created by maksing on 14/6/15.
 * Implement this abstract class to define what to do in the request.
 * @param <Result> the expected response data type.
 */
public abstract class Request<Result> {
    protected final Class<Result> expectResponseType;
    protected int maxRetryCount;

    /**
     * Default constructor, no validation of the response type
     */
    public Request() {
        this(null);
    }

    /**
     * Constructor, the response data type will need to match expectResponseType or the flow will be interrupted.
     * @param expectResponseType the class type to match the response data.
     */
    public Request(Class<Result> expectResponseType) {
        this.expectResponseType = expectResponseType;
    }

    /**
     * Define what to do in the request. session object is provided so that session.done(data) or session.fail(errorData) can be called to finish the request session.
     * @param requestSession the session instance that wrap this request.
     */
    public abstract void onCall(RequestSession<Result> requestSession);

    /**
     * Can override to provide handle when the request is done successfully before proceed to next request in the flow.
     * @param requestSession
     * @param data
     */
    public void onNext(RequestSession<Result> requestSession, Result data) {
        Debugger.log("Request " + requestSession.getId() + " responsed!! class: " + (data == null ? "null" : data.getClass() + " value: " + data));
    }

    /**
     * Can override to provide handling of failure of this request
     * @param errorResponse
     */
    public void onFailure(RequestSession<?> requestSession, Response<?> errorResponse) {
        Debugger.log("Failed error response:" + errorResponse.getData());
    }

    /**
     * Retry the request after Session.fail. please note that retries cannot be paused so it will continue to retry even Reqs.pause is called
     * as the retires together are treated as one request in the current logic.
     * @param retryCount number of retries to do after this request failed before proceed to next step.
     * @return a new Request object that can do retry.
     */
    public Request<Result> retry(int retryCount) {
        return new RetryRequest<Result>(this, retryCount);
    }

    public Class<Result> getExpectResponseType() {
        return expectResponseType;
    }

    /**
     * return the max retry count
     * @return the max retry count
     */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    static class RetryRequest<E> extends Request<E> {
        private RequestSession requestSession;
        private Request<E> request;

        public RetryRequest(final Request<E> request, final int maxRetryCount) {
            this.maxRetryCount = maxRetryCount;
            this.request = request;
        }

        private void doRequest(final Request<E> request) {

            Reqs.create(new Request() {
                @Override
                public void onCall(RequestSession requestSession) {
                    RetrySession<E> retrySession = new RetrySession<E>(requestSession, RetryRequest.this.requestSession.getReqs());
                    retrySession.setCurrentRetryCount(RetryRequest.this.requestSession.getRetryCount());
                    request.onCall(retrySession);
                }
            }).done(new Reqs.OnDoneListener() {
                @Override
                public void onSuccess(Reqs reqs, List<Response<?>> responses) {
                    requestSession.done(reqs.getAllResponses().size() == 0 ? null : reqs.getAllResponses().get(0).getData());
                }

                @Override
                public void onFailure(Response<?> failedResponse) {
                    if (requestSession.getRetryCount() < maxRetryCount) {
                        requestSession.setCurrentRetryCount(requestSession.getRetryCount() + 1);
                        request.onFailure(requestSession, failedResponse);
                        doRequest(request);
                    } else {
                        requestSession.fail(failedResponse);
                    }
                }
            }).start();
        }

        @Override
        public void onCall(RequestSession<E> requestSession) {
            this.requestSession = requestSession;
            doRequest(request);
        }

        @Override
        public void onNext(RequestSession<E> requestSession, E data) {
            request.onNext(requestSession, data);
        }

        static class RetrySession<E> extends RequestSession<E> {

            Reqs mainReqs;
            RequestSession requestSession;

            public RetrySession(RequestSession<E> requestSession, Reqs mainReqs) {
                super(requestSession.getId(), requestSession.getReqs(), requestSession.getRequest());
                this.requestSession = requestSession;
                this.mainReqs = mainReqs;
            }

            @Override
            public void done(E data) {
                super.done(data);
            }

            @Override
            public void fail(Object data) {
                requestSession.fail(data);
            }

            /**
             * return the real Reqs object, don't call this inside this class!!!!
             * @return
             */
            @Override
            public Reqs getReqs() {
                return mainReqs;
            }
        }
    }
}