package br.com.m4rc310.graphql.handlers;

import static io.leangen.graphql.spqr.spring.web.apollo.ApolloMessage.GQL_CONNECTION_INIT;
import static io.leangen.graphql.spqr.spring.web.apollo.ApolloMessage.GQL_CONNECTION_TERMINATE;
import static io.leangen.graphql.spqr.spring.web.apollo.ApolloMessage.GQL_START;
import static io.leangen.graphql.spqr.spring.web.apollo.ApolloMessage.GQL_STOP;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.m4rc310.graphql.security.MAuthToken;
import br.com.m4rc310.graphql.security.MEnumToken;
import br.com.m4rc310.graphql.security.MGraphQLJwtService;
import br.com.m4rc310.graphql.security.dto.MUser;
import br.com.m4rc310.graphql.ws.MInitMessage;
import br.com.m4rc310.graphql.ws.MMessage;
import br.com.m4rc310.graphql.ws.MMessages;
import br.com.m4rc310.graphql.ws.MStartMessage;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.leangen.graphql.spqr.spring.web.apollo.ApolloMessages;
import io.leangen.graphql.spqr.spring.web.dto.ExecutorParams;
import io.leangen.graphql.spqr.spring.web.dto.GraphQLRequest;
import io.leangen.graphql.spqr.spring.web.dto.TransportType;
import io.leangen.graphql.spqr.spring.web.mvc.websocket.GraphQLWebSocketExecutor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** The Constant log. */
@Slf4j
@Import({MGraphQLJwtService.class})
public class MTextWebSocketHandler extends TextWebSocketHandler {

	/** The graph QL. */
	private final GraphQL graphQL;
	
	/** The executor. */
	private final GraphQLWebSocketExecutor executor;
	
	/** The task scheduler. */
	private final TaskScheduler taskScheduler;
	
	/** The keep alive interval. */
	private final int keepAliveInterval;
	
	/** The subscriptions. */
	private final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();
	
	/** The keep alive. */
	private final AtomicReference<ScheduledFuture<?>> keepAlive = new AtomicReference<>();
	
	/** The jwt service. */
	@Autowired
	private MGraphQLJwtService jwtService;

	
	/**
	 * Instantiates a new m text web socket handler.
	 *
	 * @param graphQL           the graph QL
	 * @param executor          the executor
	 * @param taskScheduler     the task scheduler
	 * @param keepAliveInterval the keep alive interval
	 * @param jwtService        the jwt service
	 */
	public MTextWebSocketHandler(GraphQL graphQL, GraphQLWebSocketExecutor executor, TaskScheduler taskScheduler,
			int keepAliveInterval, MGraphQLJwtService jwtService) {
		this.graphQL = graphQL;
		this.executor = executor;
		this.taskScheduler = taskScheduler;
		this.keepAliveInterval = keepAliveInterval;
		this.jwtService = jwtService;
	}

	/**
	 * After connection established.
	 *
	 * @param session the session
	 * @throws Exception the exception
	 */
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		super.afterConnectionEstablished(session);
		if (taskScheduler != null) {
			this.keepAlive.compareAndSet(null,
					taskScheduler.scheduleWithFixedDelay(keepAliveTask(session), Math.max(keepAliveInterval, 1000)));
		}
	}

	/**
	 * After connection closed.
	 *
	 * @param session the session
	 * @param status  the status
	 */
	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		cancelAll();
		if (taskScheduler != null) {
			this.keepAlive.getAndUpdate(task -> {
				if (task != null) {
					task.cancel(false);
				}
				return null;
			});
		}
	}

	/**
	 * Handle text message.
	 *
	 * @param session the session
	 * @param message the message
	 * @throws Exception the exception
	 */
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		try {

			MMessage mmessage;
			
			try {
				mmessage = MMessages.from(message);
			} catch (Exception e) {
				session.sendMessage(MMessages.connectionError());
				e.printStackTrace();
				return;
			}


			switch (mmessage.getType()) {
			case GQL_CONNECTION_INIT:
				MUser user = fromPayload(message.getPayload());
				SecurityContextHolder.getContext().setAuthentication(new MAuthToken(user));
				session.sendMessage(MMessages.connectionAck());
				if (taskScheduler != null) {
					session.sendMessage(MMessages.keepAlive());
				}
				break;
			case GQL_START:
				GraphQLRequest request = ((MStartMessage) mmessage).getPayload();
				ExecutorParams<WebSocketSession> params = new ExecutorParams<>(request, session,
						TransportType.WEBSOCKET);
				ExecutionResult result = executor.execute(graphQL, params);
				if (result.getData() instanceof Publisher) {
					handleSubscription(mmessage.getId(), result, session);
				} else {
					handleQueryOrMutation(mmessage.getId(), result, session);
				}
				break;
			case GQL_STOP:
				Disposable toStop = subscriptions.get(mmessage.getId());
				if (toStop != null) {
					toStop.dispose();
					subscriptions.remove(mmessage.getId(), toStop);
					SecurityContextHolder.getContext().setAuthentication(null);
				}
				break;
			case GQL_CONNECTION_TERMINATE:
				session.close();
				cancelAll();
				break;
			}
		} catch (Exception e) {
			log.error(e.getMessage());
			fatalError(session, e);
		}
	}

	/**
	 * From payload.
	 *
	 * @param payload the payload
	 * @return the m user
	 * @throws Exception the exception
	 */
	private MUser fromPayload(String payload) throws Exception {

		final String BEARER = "Bearer";
		final String BASIC = "Basic";
		final String TEST = "Test";

		ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

		MInitMessage message = mapper.readValue(payload, MInitMessage.class);
		Map<String, Object> mpay = message.getPayload();

		String token = (String) mpay.get("Authorization");
		token = token.trim();

		if (token.startsWith(TEST) && jwtService.isDev()) {
			token = token.replace(TEST, "").trim();
			return jwtService.loadUserFromToken(token, MEnumToken.TEST);
		}else if (token.startsWith(BEARER)) {
			token = token.replace(BEARER, "").trim();
			return jwtService.loadUserFromToken(token, MEnumToken.BEARER);
		}else if (token.startsWith(BASIC)) {
			token = token.replace(BASIC, "").trim();
			return jwtService.loadUserFromToken(token, MEnumToken.BASIC);
		}

		return null;
	}

	/**
	 * Handle query or mutation.
	 *
	 * @param id      the id
	 * @param result  the result
	 * @param session the session
	 */
	private void handleQueryOrMutation(String id, ExecutionResult result, WebSocketSession session) {
		try {
			session.sendMessage(MMessages.data(id, result));
			session.sendMessage(MMessages.complete(id));
		} catch (IOException e) {
			fatalError(session, e);
		}
	}

	/**
	 * Handle subscription.
	 *
	 * @param id              the id
	 * @param executionResult the execution result
	 * @param session         the session
	 */
	private void handleSubscription(String id, ExecutionResult executionResult, WebSocketSession session) {
		Publisher<ExecutionResult> events = executionResult.getData();

		Disposable subscription = Flux.from(events).subscribe(result -> onNext(result, id, session),
				error -> onError(error, id, session), () -> onComplete(id, session));
		synchronized (subscriptions) {
			subscriptions.put(id, subscription);
		}
	}

	/**
	 * Handle transport error.
	 *
	 * @param session   the session
	 * @param exception the exception
	 */
	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		fatalError(session, exception);
	}

	/**
	 * On next.
	 *
	 * @param result  the result
	 * @param id      the id
	 * @param session the session
	 */
	private void onNext(ExecutionResult result, String id, WebSocketSession session) {
		try {
			if (result.getErrors().isEmpty()) {
				session.sendMessage(MMessages.data(id, result));
			} else {
				session.sendMessage(MMessages.error(id, result.getErrors()));
			}
		} catch (IOException e) {
			fatalError(session, e);
		}
	}

	/**
	 * On error.
	 *
	 * @param error   the error
	 * @param id      the id
	 * @param session the session
	 */
	private void onError(Throwable error, String id, WebSocketSession session) {
		try {
			session.sendMessage(MMessages.error(id, error));
			session.sendMessage(MMessages.complete(id));
		} catch (IOException e) {
			fatalError(session, e);
		}
	}

	/**
	 * On complete.
	 *
	 * @param id      the id
	 * @param session the session
	 */
	private void onComplete(String id, WebSocketSession session) {
		try {
			session.sendMessage(MMessages.complete(id));
		} catch (IOException e) {
			fatalError(session, e);
		}
	}

	/**
	 * Cancel all.
	 */
	void cancelAll() {
		synchronized (subscriptions) {
			subscriptions.values().forEach(Disposable::dispose);
			subscriptions.clear();
		}
	}

	/**
	 * Fatal error.
	 *
	 * @param session   the session
	 * @param exception the exception
	 */
	private void fatalError(WebSocketSession session, Throwable exception) {
		try {
			session.close(
					exception instanceof IOException ? CloseStatus.SESSION_NOT_RELIABLE : CloseStatus.SERVER_ERROR);
		} catch (Exception suppressed) {
			exception.addSuppressed(suppressed);
		}
		cancelAll();
		// log.warn(String.format("WebSocket session %s (%s) closed due to an
		// exception", session.getId(), session.getRemoteAddress()), exception);
	}

	/**
	 * Keep alive task.
	 *
	 * @param session the session
	 * @return the runnable
	 */
	private Runnable keepAliveTask(WebSocketSession session) {
		return () -> {
			try {
				if (session != null && session.isOpen()) {
					session.sendMessage(ApolloMessages.keepAlive());
				}
			} catch (IOException exception) {
				fatalError(session, exception);
			}
		};
	}

}
