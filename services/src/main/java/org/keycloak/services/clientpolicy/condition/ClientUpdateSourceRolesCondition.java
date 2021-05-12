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

package org.keycloak.services.clientpolicy.condition;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.ClientPolicyVote;
import org.keycloak.services.clientpolicy.context.AdminClientRegisterContext;
import org.keycloak.services.clientpolicy.context.AdminClientUpdateContext;
import org.keycloak.services.clientpolicy.context.ClientCRUDContext;
import org.keycloak.services.clientpolicy.context.DynamicClientRegisterContext;
import org.keycloak.services.clientpolicy.context.DynamicClientUpdateContext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author <a href="mailto:takashi.norimatsu.ws@hitachi.com">Takashi Norimatsu</a>
 */
public class ClientUpdateSourceRolesCondition implements ClientPolicyConditionProvider<ClientUpdateSourceRolesCondition.Configuration> {

    private static final Logger logger = Logger.getLogger(ClientUpdateSourceRolesCondition.class);

    // to avoid null configuration, use vacant new instance to indicate that there is no configuration set up.
    private Configuration configuration = new Configuration();
    private final KeycloakSession session;

    public ClientUpdateSourceRolesCondition(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void setupConfiguration(Configuration config) {
        this.configuration = config;
    }

    @Override
    public Class<Configuration> getConditionConfigurationClass() {
        return Configuration.class;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Configuration extends ClientPolicyConditionConfiguration {
        @JsonProperty("is-negative-logic")
        protected Boolean negativeLogic;

        public Boolean isNegativeLogic() {
            return negativeLogic;
        }

        public void setNegativeLogic(Boolean negativeLogic) {
            this.negativeLogic = negativeLogic;
        }

        protected List<String> roles;

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    @Override
    public boolean isNegativeLogic() {
        return Optional.ofNullable(this.configuration.isNegativeLogic()).orElse(Boolean.FALSE).booleanValue();
    }

    @Override
    public String getProviderId() {
        return ClientUpdateSourceRolesConditionFactory.PROVIDER_ID;
    }

    @Override
    public ClientPolicyVote applyPolicy(ClientPolicyContext context) throws ClientPolicyException {
        switch (context.getEvent()) {
        case REGISTER:
            if (context instanceof AdminClientRegisterContext) {
                return getVoteForRolesMatched(((ClientCRUDContext)context).getAuthenticatedUser());
            } else if (context instanceof DynamicClientRegisterContext) {
                return getVoteForRolesMatched(((ClientCRUDContext)context).getToken());
            } else {
                throw new ClientPolicyException(OAuthErrorException.SERVER_ERROR, "unexpected context type.");
            }
        case UPDATE:
            if (context instanceof AdminClientUpdateContext) {
                return getVoteForRolesMatched(((ClientCRUDContext)context).getAuthenticatedUser());
            } else if (context instanceof DynamicClientUpdateContext) {
                return getVoteForRolesMatched(((ClientCRUDContext)context).getToken());
            } else {
                throw new ClientPolicyException(OAuthErrorException.SERVER_ERROR, "unexpected context type.");
            }
        default:
            return ClientPolicyVote.ABSTAIN;
        }
    }

    private ClientPolicyVote getVoteForRolesMatched(UserModel user) {
        if (isRolesMatched(user)) return ClientPolicyVote.YES;
        return ClientPolicyVote.NO;
    }

    private ClientPolicyVote getVoteForRolesMatched(JsonWebToken token) {
        if (token == null) return ClientPolicyVote.NO;
        if(isRoleMatched(token.getSubject())) return ClientPolicyVote.YES;
        return ClientPolicyVote.NO;
    }

    private boolean isRoleMatched(String subjectId) {
        if (subjectId == null) return false;
        return isRolesMatched(session.users().getUserById(session.getContext().getRealm(), subjectId));
    }

    private boolean isRolesMatched(UserModel user) {
        if (user == null) return false;

        Set<String> expectedRoles = instantiateRolesForMatching();
        if (expectedRoles == null) return false;

        // user.getRoleMappingsStream() never returns null according to {@link UserModel.getRoleMappingsStream}
        Set<String> roles = user.getRoleMappingsStream().map(RoleModel::getName).collect(Collectors.toSet());

        if (logger.isTraceEnabled()) {
            roles.forEach(i -> logger.tracev("user role = {0}", i));
            expectedRoles.forEach(i -> logger.tracev("roles expected = {0}", i));
        }

        RealmModel realm = session.getContext().getRealm();
        boolean isMatched = expectedRoles.stream().anyMatch(i->{
            if (realm.getRole(i) != null && user.hasRole(realm.getRole(i))) {
                return true;
            }
            return realm.getClientsStream().anyMatch(j->{
                if (j.getRole(i) != null && user.hasRole(j.getRole(i))) {
                    return true;
                }
                return false;
            });
        });

        return isMatched;
    }

    private Set<String> instantiateRolesForMatching() {
        List<String> roles = configuration.getRoles();
        if (roles == null) return null;
        return new HashSet<>(roles);
    }

}
