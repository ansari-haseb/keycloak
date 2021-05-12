/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.model.session;

import org.junit.Assert;
import org.junit.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.sessions.infinispan.changes.sessions.PersisterLastSessionRefreshStoreFactory;
import org.keycloak.models.utils.ResetTimeOffsetEvent;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.testsuite.model.infinispan.InfinispanTestUtil;
import org.keycloak.timer.TimerProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.keycloak.testsuite.model.KeycloakModelTest;
import org.keycloak.testsuite.model.RequireProvider;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 * @author <a href="mailto:mkanis@redhat.com">Martin Kanis</a>
 */
@RequireProvider(UserSessionPersisterProvider.class)
@RequireProvider(value=UserSessionProvider.class, only={"infinispan"})
@RequireProvider(UserProvider.class)
@RequireProvider(RealmProvider.class)
public class UserSessionProviderOfflineModelTest extends KeycloakModelTest {

    private String realmId;
    private KeycloakSession kcSession;

    private UserSessionManager sessionManager;
    private UserSessionPersisterProvider persister;

    @Override
    public void createEnvironment(KeycloakSession s) {
        RealmModel realm = s.realms().createRealm("test");
        realm.setOfflineSessionIdleTimeout(Constants.DEFAULT_OFFLINE_SESSION_IDLE_TIMEOUT);
        realm.setDefaultRole(s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
        this.realmId = realm.getId();
        this.kcSession = s;

        s.users().addUser(realm, "user1").setEmail("user1@localhost");
        s.users().addUser(realm, "user2").setEmail("user2@localhost");

        UserSessionPersisterProviderTest.createClients(s, realm);
    }

    @Override
    public void cleanEnvironment(KeycloakSession s) {
        RealmModel realm = s.realms().getRealm(realmId);
        s.sessions().removeUserSessions(realm);

        UserModel user1 = s.users().getUserByUsername(realm, "user1");
        UserModel user2 = s.users().getUserByUsername(realm, "user2");

        UserManager um = new UserManager(s);
        if (user1 != null) {
            um.removeUser(realm, user1);
        }
        if (user2 != null) {
            um.removeUser(realm, user2);
        }

        s.realms().removeRealm(realmId);
    }

    @Test
    public void testExpired() {
        // Suspend periodic tasks to avoid race-conditions, which may cause missing updates of lastSessionRefresh times to UserSessionPersisterProvider
        TimerProvider timer = kcSession.getProvider(TimerProvider.class);
        TimerProvider.TimerTaskContext timerTaskCtx = null;
        if (timer != null) {
            timerTaskCtx = timer.cancelTask(PersisterLastSessionRefreshStoreFactory.DB_LSR_PERIODIC_TASK_NAME);
            log.info("Cancelled periodic task " + PersisterLastSessionRefreshStoreFactory.DB_LSR_PERIODIC_TASK_NAME);
        }

        InfinispanTestUtil.setTestingTimeService(kcSession);

        try {
            // Key is userSessionId, value is set of client UUIDS
            Map<String, Set<String>> offlineSessions = new HashMap<>();
            ClientModel[] testApp = new ClientModel[1];

            UserSessionModel[] origSessions = inComittedTransaction(session -> {
                // Create some online sessions in infinispan
                return UserSessionPersisterProviderTest.createSessions(session, realmId);
            });

            inComittedTransaction(session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                sessionManager = new UserSessionManager(session);
                persister = session.getProvider(UserSessionPersisterProvider.class);

                // Persist 3 created userSessions and clientSessions as offline
                testApp[0] = realm.getClientByClientId("test-app");
                session.sessions().getUserSessionsStream(realm, testApp[0]).collect(Collectors.toList())
                        .forEach(userSession -> offlineSessions.put(userSession.getId(), createOfflineSessionIncludeClientSessions(session, userSession)));

                // Assert all previously saved offline sessions found
                for (Map.Entry<String, Set<String>> entry : offlineSessions.entrySet()) {
                    UserSessionModel foundSession = sessionManager.findOfflineUserSession(realm, entry.getKey());
                    Assert.assertEquals(foundSession.getAuthenticatedClientSessions().keySet(), entry.getValue());
                }
            });

            log.info("Persisted 3 sessions to UserSessionPersisterProvider");

            inComittedTransaction(session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                persister = session.getProvider(UserSessionPersisterProvider.class);

                UserSessionModel session0 = session.sessions().getOfflineUserSession(realm, origSessions[0].getId());
                Assert.assertNotNull(session0);

                // sessions are in persister too
                Assert.assertEquals(3, persister.getUserSessionsCount(true));

                Time.setOffset(300);
                log.infof("Set time offset to 300. Time is: %d", Time.currentTime());

                // Set lastSessionRefresh to currentSession[0] to 0
                session0.setLastSessionRefresh(Time.currentTime());
            });


            // Increase timeOffset and update LSR of the session two times - first to 20 days and then to 21 days. At least one of updates
            // will propagate to PersisterLastSessionRefreshStore and update DB (Single update is not 100% sure as there is still a
            // chance of delayed periodic task to be run in the meantime and causing race-condition, which would mean LSR not updated in the DB)
            IntStream.range(0, 2).sequential().forEach(index -> inComittedTransaction(index, (session, i) -> {
                int timeOffset = 1728000 + (i * 86400);

                RealmModel realm = session.realms().getRealm(realmId);
                Time.setOffset(timeOffset);
                log.infof("Set time offset to %d. Time is: %d", timeOffset, Time.currentTime());

                UserSessionModel session0 = session.sessions().getOfflineUserSession(realm, origSessions[0].getId());
                session0.setLastSessionRefresh(Time.currentTime());

                return null;
            }));

            inComittedTransaction(session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                persister = session.getProvider(UserSessionPersisterProvider.class);

                // Increase timeOffset - 40 days
                Time.setOffset(3456000);
                log.infof("Set time offset to 3456000. Time is: %d", Time.currentTime());

                // Expire and ensure that all sessions despite session0 were removed
                persister.removeExpired(realm);
            });

            inComittedTransaction(session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                persister = session.getProvider(UserSessionPersisterProvider.class);

                // assert session0 is the only session found
                Assert.assertNotNull(session.sessions().getOfflineUserSession(realm, origSessions[0].getId()));
                Assert.assertNull(session.sessions().getOfflineUserSession(realm, origSessions[1].getId()));
                Assert.assertNull(session.sessions().getOfflineUserSession(realm, origSessions[2].getId()));

                Assert.assertEquals(1, persister.getUserSessionsCount(true));

                // Expire everything and assert nothing found
                Time.setOffset(7000000);

                persister.removeExpired(realm);
            });

            inComittedTransaction(session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                sessionManager = new UserSessionManager(session);
                persister = session.getProvider(UserSessionPersisterProvider.class);

                for (String userSessionId : offlineSessions.keySet()) {
                    Assert.assertNull(sessionManager.findOfflineUserSession(realm, userSessionId));
                }
                Assert.assertEquals(0, persister.getUserSessionsCount(true));
            });

        } finally {
            Time.setOffset(0);
            kcSession.getKeycloakSessionFactory().publish(new ResetTimeOffsetEvent());
            if (timer != null) {
                timer.schedule(timerTaskCtx.getRunnable(), timerTaskCtx.getIntervalMillis(), PersisterLastSessionRefreshStoreFactory.DB_LSR_PERIODIC_TASK_NAME);
            }

            InfinispanTestUtil.revertTimeService(kcSession);
        }
    }

    private static Set<String> createOfflineSessionIncludeClientSessions(KeycloakSession session, UserSessionModel
            userSession) {
        Set<String> offlineSessions = new HashSet<>();
        UserSessionManager localManager = new UserSessionManager(session);
        for (AuthenticatedClientSessionModel clientSession : userSession.getAuthenticatedClientSessions().values()) {
            localManager.createOrUpdateOfflineSession(clientSession, userSession);
            offlineSessions.add(clientSession.getClient().getId());
        }

        return offlineSessions;
    }
}
