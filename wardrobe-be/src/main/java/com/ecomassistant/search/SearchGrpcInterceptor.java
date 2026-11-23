package com.ecomassistant.search;

import io.grpc.*;
import java.util.concurrent.TimeUnit;

/** Channel-wide hook is inert for startup, browsing and health calls. */
public final class SearchGrpcInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions options, Channel next) {
        var budget = SearchBudget.current();
        if (budget == null) return next.newCall(method, options);
        var deadline = Deadline.after(budget.remainingNanos(), TimeUnit.NANOSECONDS);
        if (options.getDeadline() != null) deadline = deadline.minimum(options.getDeadline());
        var call = next.newCall(method, options.withDeadline(deadline));
        return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
            @Override public void start(Listener<RespT> listener, Metadata headers) {
                var registration = budget.onCancel(() -> call.cancel("Search timed out", null));
                try {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                        @Override public void onClose(Status status, Metadata trailers) {
                            try { registration.close(); } catch (Exception ignored) { }
                            super.onClose(status, trailers);
                        }
                    }, headers);
                } catch (RuntimeException ex) {
                    try { registration.close(); } catch (Exception ignored) { }
                    throw ex;
                }
            }
        };
    }
}
