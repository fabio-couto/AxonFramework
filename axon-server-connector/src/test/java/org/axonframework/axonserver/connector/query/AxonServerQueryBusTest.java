/*
 * Copyright (c) 2010-2021. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.axonserver.connector.query;

import com.google.protobuf.ByteString;
import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.axonserver.connector.ResultStream;
import io.axoniq.axonserver.connector.query.QueryChannel;
import io.axoniq.axonserver.connector.query.QueryDefinition;
import io.axoniq.axonserver.connector.query.QueryHandler;
import io.axoniq.axonserver.grpc.ErrorMessage;
import io.axoniq.axonserver.grpc.SerializedObject;
import io.axoniq.axonserver.grpc.query.QueryResponse;
import io.axoniq.axonserver.grpc.query.QueryUpdate;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.ErrorCode;
import org.axonframework.axonserver.connector.TargetContextResolver;
import org.axonframework.axonserver.connector.TestTargetContextResolver;
import org.axonframework.axonserver.connector.util.ProcessingInstructionHelper;
import org.axonframework.axonserver.connector.utils.TestSerializer;
import org.axonframework.common.Registration;
import org.axonframework.lifecycle.ShutdownInProgressException;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.responsetypes.InstanceResponseType;
import org.axonframework.queryhandling.GenericQueryMessage;
import org.axonframework.queryhandling.GenericSubscriptionQueryMessage;
import org.axonframework.queryhandling.QueryBus;
import org.axonframework.queryhandling.QueryExecutionException;
import org.axonframework.queryhandling.QueryMessage;
import org.axonframework.queryhandling.QueryResponseMessage;
import org.axonframework.queryhandling.SimpleQueryUpdateEmitter;
import org.axonframework.queryhandling.SubscriptionQueryMessage;
import org.axonframework.queryhandling.SubscriptionQueryResult;
import org.axonframework.queryhandling.SubscriptionQueryUpdateMessage;
import org.axonframework.serialization.Serializer;
import org.junit.jupiter.api.*;
import org.mockito.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.axonframework.axonserver.connector.utils.AssertUtils.assertWithin;
import static org.axonframework.messaging.responsetypes.ResponseTypes.instanceOf;
import static org.axonframework.messaging.responsetypes.ResponseTypes.optionalInstanceOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test suite to verify the {@link AxonServerQueryBus}.
 *
 * @author Marc Gathier
 */
class AxonServerQueryBusTest {

    private static final String TEST_QUERY = "testQuery";
    private static final String CONTEXT = "default-test";

    private final QueryBus localSegment = mock(QueryBus.class);
    private final Serializer serializer = TestSerializer.xStreamSerializer();
    private final TargetContextResolver<QueryMessage<?, ?>> targetContextResolver = spy(new TestTargetContextResolver<>());

    private AxonServerConnectionManager axonServerConnectionManager;
    private QueryChannel mockQueryChannel;

    private AxonServerQueryBus testSubject;

    @BeforeEach
    void setup() {
        AxonServerConfiguration configuration = new AxonServerConfiguration();
        configuration.setContext(CONTEXT);
        axonServerConnectionManager = mock(AxonServerConnectionManager.class);

        testSubject = AxonServerQueryBus.builder()
                                        .axonServerConnectionManager(axonServerConnectionManager)
                                        .configuration(configuration)
                                        .localSegment(localSegment)
                                        .updateEmitter(SimpleQueryUpdateEmitter.builder().build())
                                        .messageSerializer(serializer)
                                        .genericSerializer(serializer)
                                        .targetContextResolver(targetContextResolver)
                                        .build();

        AxonServerConnection mockConnection = mock(AxonServerConnection.class);
        mockQueryChannel = mock(QueryChannel.class);

        when(axonServerConnectionManager.getConnection(anyString())).thenReturn(mockConnection);
        when(axonServerConnectionManager.getConnection()).thenReturn(mockConnection);

        when(mockConnection.queryChannel()).thenReturn(mockQueryChannel);
        when(mockQueryChannel.registerQueryHandler(any(), any()))
                .thenReturn(() -> CompletableFuture.completedFuture(null));

        when(localSegment.subscribe(any(), any(), any())).thenReturn(() -> true);
    }

    @AfterEach
    void tearDown() throws Exception {
        axonServerConnectionManager.shutdown();
        testSubject.shutdownDispatching().get(5, TimeUnit.SECONDS);
        testSubject.disconnect();
    }

    @Test
    void subscribe() {
        Registration result = testSubject.subscribe(TEST_QUERY, String.class, q -> "test");

        assertNotNull(result);
        verify(axonServerConnectionManager).getConnection(CONTEXT);
        verify(mockQueryChannel).registerQueryHandler(any(), eq(new QueryDefinition(TEST_QUERY, String.class)));
    }

    @Test
    void testSeveralSubscribeInvocationsUseSameQueryHandlerInstance() {
        QueryDefinition firstExpectedQueryDefinition = new QueryDefinition(TEST_QUERY, String.class);
        QueryDefinition secondExpectedQueryDefinition = new QueryDefinition("testIntegerQuery", Integer.class);

        ArgumentCaptor<QueryHandler> queryHandlerCaptor = ArgumentCaptor.forClass(QueryHandler.class);

        Registration resultOne = testSubject.subscribe(TEST_QUERY, String.class, q -> "test");
        assertNotNull(resultOne);
        verify(mockQueryChannel).registerQueryHandler(queryHandlerCaptor.capture(), eq(firstExpectedQueryDefinition));

        Registration resultTwo = testSubject.subscribe("testIntegerQuery", Integer.class, q -> 1337);
        assertNotNull(resultTwo);
        verify(mockQueryChannel).registerQueryHandler(queryHandlerCaptor.capture(), eq(secondExpectedQueryDefinition));

        List<QueryHandler> resultQueryHandlers = queryHandlerCaptor.getAllValues();
        assertEquals(2, resultQueryHandlers.size());
        assertEquals(resultQueryHandlers.get(0), resultQueryHandlers.get(1));
    }

    @Test
    void query() throws Exception {
        when(mockQueryChannel.query(any())).thenReturn(new StubResultStream(stubResponse("<string>test</string>")));
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("Hello, World", instanceOf(String.class));

        assertEquals("test", testSubject.query(testQuery).get().getPayload());

        verify(targetContextResolver).resolveContext(testQuery);
    }

    @Test
    void queryReportsDispatchException() throws Exception {
        StubResultStream t = new StubResultStream(new RuntimeException("Faking problems"));
        when(mockQueryChannel.query(any())).thenReturn(t);
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("Hello, World", instanceOf(String.class));

        CompletableFuture<QueryResponseMessage<String>> result = testSubject.query(testQuery);
        try {
            result.get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AxonServerQueryDispatchException);
            assertEquals("Faking problems", e.getCause().getMessage());
        }

        verify(targetContextResolver).resolveContext(testQuery);
    }

    @Test
    void testQueryReportsCorrectException() throws ExecutionException, InterruptedException {
        when(mockQueryChannel.query(any())).thenReturn(new StubResultStream(
                stubErrorResponse(ErrorCode.QUERY_EXECUTION_ERROR.errorCode(), "Faking exception result")
        ));
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("Hello, World", instanceOf(String.class));

        CompletableFuture<QueryResponseMessage<String>> result = testSubject.query(testQuery);

        assertNotNull(result.get());
        assertFalse(result.isCompletedExceptionally());

        assertTrue(result.get().isExceptional());
        Throwable actual = result.get().exceptionResult();
        assertTrue(actual instanceof QueryExecutionException);
        AxonServerRemoteQueryHandlingException queryDispatchException =
                (AxonServerRemoteQueryHandlingException) actual.getCause();
        assertEquals(ErrorCode.QUERY_EXECUTION_ERROR.errorCode(), queryDispatchException.getErrorCode());

        verify(targetContextResolver).resolveContext(testQuery);
    }

    @Test
    void subscribeHandler() {
        when(mockQueryChannel.registerQueryHandler(any(), any()))
                .thenReturn(() -> CompletableFuture.completedFuture(null));

        Registration result = testSubject.subscribe(TEST_QUERY, String.class, q -> "test: " + q.getPayloadType());

        assertNotNull(result);
        verify(mockQueryChannel).registerQueryHandler(any(), eq(new QueryDefinition(TEST_QUERY, String.class)));
    }

    @Test
    void unsubscribeHandler() {
        io.axoniq.axonserver.connector.Registration registration = mock(io.axoniq.axonserver.connector.Registration.class);
        when(mockQueryChannel.registerQueryHandler(any(), any())).thenReturn(registration);

        Registration result = testSubject.subscribe(TEST_QUERY, String.class, q -> "test: " + q.getPayloadType());
        assertNotNull(result);
        verify(mockQueryChannel).registerQueryHandler(any(), eq(new QueryDefinition(TEST_QUERY, String.class)));

        result.close();
        verify(registration).cancel();
    }

    @Test
    void scatterGather() {
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("Hello, World", instanceOf(String.class));

        when(mockQueryChannel.query(any())).thenReturn(new StubResultStream(stubResponse("<string>1</string>"),
                                                                            stubResponse("<string>2</string>"),
                                                                            stubResponse("<string>3</string>")));

        assertEquals(3, testSubject.scatterGather(testQuery, 12, TimeUnit.SECONDS).count());

        verify(targetContextResolver).resolveContext(testQuery);
        verify(mockQueryChannel).query(argThat(
                r -> r.getPayload().getData().toStringUtf8().equals("<string>Hello, World</string>")
                        && -1 == ProcessingInstructionHelper.numberOfResults(r.getProcessingInstructionsList())));
    }

    @Test
    void queryForOptionalWillRequestInstanceOfFromRemoteDestination() {
        QueryMessage<String, Optional<String>> testQuery =
                new GenericQueryMessage<>("Hello, World", optionalInstanceOf(String.class));

        Stream<QueryResponseMessage<Optional<String>>> actual = testSubject.scatterGather(testQuery, 12, TimeUnit.SECONDS);
        // not really interested in the result
        actual.close();

        verify(targetContextResolver).resolveContext(testQuery);
        verify(mockQueryChannel).query(argThat(
                r -> r.getResponseType().getType().equals(InstanceResponseType.class.getName())
        ));
    }

    @Test
    void dispatchInterceptor() {
        List<Object> results = new LinkedList<>();
        testSubject.registerDispatchInterceptor(messages -> (a, b) -> {
            results.add(b.getPayload());
            return b;
        });

        testSubject.query(new GenericQueryMessage<>("payload", new InstanceResponseType<>(String.class)));
        assertEquals("payload", results.get(0));
        assertEquals(1, results.size());
    }

    @Test
    void handlerInterceptorRegisteredWithLocalSegment() {
        MessageHandlerInterceptor<QueryMessage<?, ?>> interceptor =
                (unitOfWork, interceptorChain) -> interceptorChain.proceed();

        testSubject.registerHandlerInterceptor(interceptor);

        verify(localSegment).registerHandlerInterceptor(interceptor);
    }

    @Test
    void testLocalSegmentReturnsLocalQueryBus() {
        assertEquals(localSegment, testSubject.localSegment());
    }

    @Test
    void testAfterShutdownDispatchingAnShutdownInProgressExceptionOnQueryInvocation() {
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("some-query", instanceOf(String.class));

        assertDoesNotThrow(() -> testSubject.shutdownDispatching().get(5, TimeUnit.SECONDS));

        assertWithin(
                50, TimeUnit.MILLISECONDS,
                () -> assertThrows(ShutdownInProgressException.class, () -> testSubject.query(testQuery))
        );
    }

    @Test
    void testShutdownTakesFinishedQueriesIntoAccount() {
        when(mockQueryChannel.query(any())).thenReturn(new StubResultStream(QueryResponse.newBuilder().build()));
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("some-query", instanceOf(String.class));

        CompletableFuture<QueryResponseMessage<String>> result = testSubject.query(testQuery);
        result.join();

        assertDoesNotThrow(() -> testSubject.shutdownDispatching().get(5, TimeUnit.SECONDS));

    }

    @Test
    void testAfterShutdownDispatchingAnShutdownInProgressExceptionOnScatterGatherInvocation() {
        QueryMessage<String, String> testQuery = new GenericQueryMessage<>("some-query", instanceOf(String.class));

        assertDoesNotThrow(() -> testSubject.shutdownDispatching().get(5, TimeUnit.SECONDS));

        assertWithin(
                50, TimeUnit.MILLISECONDS,
                () -> assertThrows(
                        ShutdownInProgressException.class,
                        () -> testSubject.scatterGather(testQuery, 1, TimeUnit.SECONDS)
                )
        );
    }

    @Test
    void testSubscriptionQueryCompletesWithExceptionOnUpdateDeserializationError() {
        when(mockQueryChannel.subscriptionQuery(any(), any(), anyInt(), anyInt()))
                .thenReturn(new SimpleSubscriptionQueryResult("<string>Hello world</string>", stubUpdate("Not a valid XML object")));
        SubscriptionQueryResult<QueryResponseMessage<String>, SubscriptionQueryUpdateMessage<String>> queryResult = testSubject.subscriptionQuery(new GenericSubscriptionQueryMessage<>("Say hi", "test", instanceOf(String.class), instanceOf(String.class)));
        Mono<QueryResponseMessage<String>> initialResult = queryResult.initialResult();
        Flux<SubscriptionQueryUpdateMessage<String>> updates = queryResult.updates();
        queryResult.close();

        StepVerifier.create(initialResult)
                    .expectNextMatches(r -> r.getPayload().equals("Hello world"))
                    .verifyComplete();

        StepVerifier.create(updates.map(Message::getPayload))
                    .verifyError();
    }

    @Test
    void testSubscriptionQueryCompletesWithExceptionOnInitialResultDeserializationError() {
        when(mockQueryChannel.subscriptionQuery(any(), any(), anyInt(), anyInt()))
                .thenReturn(new SimpleSubscriptionQueryResult("Not a valid XML object", stubUpdate("<string>Hello world</string>")));
        SubscriptionQueryResult<QueryResponseMessage<String>, SubscriptionQueryUpdateMessage<String>> queryResult = testSubject.subscriptionQuery(new GenericSubscriptionQueryMessage<>("Say hi", "test", instanceOf(String.class), instanceOf(String.class)));
        Mono<QueryResponseMessage<String>> initialResult = queryResult.initialResult();
        Flux<SubscriptionQueryUpdateMessage<String>> updates = queryResult.updates();
        queryResult.close();

        StepVerifier.create(initialResult.map(Message::getPayload))
                    .verifyError();

        StepVerifier.create(updates.map(Message::getPayload))
                    .expectNextMatches(r -> r.equals("Hello world"))
                    .verifyComplete();
    }

    @Test
    void testAfterShutdownDispatchingAnShutdownInProgressExceptionOnSubscriptionQueryInvocation() {
        SubscriptionQueryMessage<String, String, String> testSubscriptionQuery =
                new GenericSubscriptionQueryMessage<>("some-query", instanceOf(String.class), instanceOf(String.class));

        assertDoesNotThrow(() -> testSubject.shutdownDispatching().get(5, TimeUnit.SECONDS));

        assertThrows(ShutdownInProgressException.class,
                     () -> testSubject.subscriptionQuery(testSubscriptionQuery));
    }

    private QueryResponse stubResponse(String payload) {
        return QueryResponse.newBuilder()
                            .setRequestIdentifier("request")
                            .setMessageIdentifier(UUID.randomUUID().toString())
                            .setPayload(SerializedObject.newBuilder()
                                                        .setData(ByteString.copyFromUtf8(payload))
                                                        .setType(String.class.getName()))
                            .build();
    }

    private QueryUpdate stubUpdate(String payload) {
        return QueryUpdate.newBuilder()
                          .setMessageIdentifier(UUID.randomUUID().toString())
                          .setPayload(SerializedObject.newBuilder()
                                                      .setData(ByteString.copyFromUtf8(payload))
                                                      .setType(String.class.getName()))
                          .build();
    }

    private QueryResponse stubErrorResponse(String errorCode, @SuppressWarnings("SameParameterValue") String message) {
        return QueryResponse.newBuilder()
                            .setRequestIdentifier("request")
                            .setMessageIdentifier(UUID.randomUUID().toString())
                            .setErrorCode(errorCode)
                            .setErrorMessage(ErrorMessage.newBuilder()
                                                         .setMessage(message)
                                                         .setLocation("test")
                                                         .build())
                            .build();
    }

    private static class StubResultStream<T> implements ResultStream<T> {

        private final Iterator<T> responses;
        private final Throwable error;
        private T peeked;
        private boolean closed;

        public StubResultStream(Throwable error) {
            this.error = error;
            this.closed = true;
            this.responses = Collections.emptyIterator();
        }

        public StubResultStream(T... responses) {
            this.error = null;
            this.responses = Arrays.asList(responses).iterator();
        }

        @Override
        public T peek() {
            if (peeked == null && responses.hasNext()) {
                peeked = responses.next();
            }
            return peeked;
        }

        @Override
        public T nextIfAvailable() {
            if (peeked != null) {
                T result = peeked;
                peeked = null;
                return result;
            }
            return responses.hasNext() ? responses.next() : null;
        }

        @Override
        public T nextIfAvailable(long timeout, TimeUnit unit) {
            return nextIfAvailable();
        }

        @Override
        public T next() {
            return nextIfAvailable();
        }

        @Override
        public void onAvailable(Runnable r) {
            if (peeked != null || responses.hasNext() || isClosed()) {
                r.run();
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public Optional<Throwable> getError() {
            return Optional.ofNullable(error);
        }
    }

    private class SimpleSubscriptionQueryResult implements io.axoniq.axonserver.connector.query.SubscriptionQueryResult {
        private final StubResultStream<QueryUpdate> updateStubResultStream;
        private final String payload;

        public SimpleSubscriptionQueryResult(String payload, QueryUpdate... updates) {
            this.updateStubResultStream = new StubResultStream<>(updates);
            this.payload = payload;
        }

        @Override
        public CompletableFuture<QueryResponse> initialResult() {
            return CompletableFuture.completedFuture(stubResponse(payload));
        }

        @Override
        public ResultStream<QueryUpdate> updates() {
            return updateStubResultStream;
        }
    }
}
