package com.library.auth.application.impl;

import com.library.auth.application.IntrospectToken;
import com.library.auth.application.LogoutUseCase;
import com.library.auth.application.RefreshTokensService;
import com.library.auth.application.enums.PurposeToken;
import com.library.auth.properties.RSAKeyRecord;
import com.library.shared.exception.AppException;
import com.library.shared.exception.ErrorCode;
import com.library.shared.util.StaticVariable;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutUseCaseImpl implements LogoutUseCase {
    private final IntrospectToken introspectToken;
    private final RSAKeyRecord rSAKeyRecord;
    private final RefreshTokensService refreshTokensService;
    private final org.springframework.beans.factory.ObjectProvider<com.library.shared.port.ChatConversationRevocationPort> conversations;

    @Override
    public void execute(String refreshToken) {
        JWTClaimsSet claimsSet = introspectToken.parseAndVerifyToken(refreshToken, rSAKeyRecord.rsaPublicKey());
        if (claimsSet == null || !PurposeToken.REFRESH.name().equals(claimsSet.getClaim(StaticVariable.PURPOSE))) {
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }
        refreshTokensService.revokeToken(claimsSet.getJWTID());
        if(conversations!=null) {
            try {
                Long owner=Long.valueOf(claimsSet.getSubject());
                conversations.ifAvailable(store->store.revoke(owner));
            } catch(RuntimeException ignored) { /* Cache must not prevent logout. */ }
        }
    }
}
