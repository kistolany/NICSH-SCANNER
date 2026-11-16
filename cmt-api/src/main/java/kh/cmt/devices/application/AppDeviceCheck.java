package kh.cmt.devices.application;

import java.util.UUID;
import kh.cmt.devices.api.AppDevices;
import kh.cmt.devices.domain.AppVersions;
import kh.cmt.devices.infrastructure.AuthDeviceStore;
import kh.cmt.shared.security.SchoolContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The phone check for other modules (the scan): linked phone, block, minimum version, the school's device rules. */
@Service
class AppDeviceCheck implements AppDevices {

    private final AuthDeviceStore store;
    private final SchoolContext schoolContext;

    AppDeviceCheck(AuthDeviceStore store, SchoolContext schoolContext) {
        this.store = store;
        this.schoolContext = schoolContext;
    }

    @Override
    @Transactional(readOnly = true)
    public Check check(UUID accountId, UUID tokenDeviceId, String appVersion) {
        AuthDeviceStore.Policy policy = store.policy(schoolContext.currentSchoolId());
        AuthDeviceStore.AccountDevice d = store.ofAccount(accountId).orElse(null);
        String key = d == null ? null : publicKey(accountId);
        Result result;
        if (d == null || d.deviceId() == null || tokenDeviceId == null || !d.deviceId().equals(tokenDeviceId)) {
            result = Result.DEVICE_MISMATCH;   // the token was issued to another phone (or the phone was reset since)
        } else if (d.blocked()) {
            result = Result.DEVICE_BLOCKED;
        } else if (appVersion != null && AppVersions.older(appVersion, policy.minAppVersion())) {
            result = Result.UPDATE_REQUIRED;
        } else {
            result = Result.OK;
        }
        return new Check(result, d == null ? null : d.deviceId(), policy.minAppVersion(), key, policy.blockRooted(), policy.blockMockLocation());
    }

    private String publicKey(UUID accountId) {
        return store.publicKey(accountId).orElse(null);
    }
}
