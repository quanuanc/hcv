package dev.cheng.vaultstarter;

import java.time.Duration;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;

/** Own scheduler: does not enable or alter scheduling in the consuming application. */
final class VaultRefreshTask implements InitializingBean, DisposableBean {
    private final List<RefreshingVaultSecret<?>> secrets;
    private final Duration tick;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    VaultRefreshTask(List<RefreshingVaultSecret<?>> secrets, Duration tick) {
        Assert.isTrue(tick != null && tick.toMillis() > 0, "Vault refresh tick must be positive");
        this.secrets = List.copyOf(secrets);
        this.tick = tick;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler.setThreadNamePrefix("hcv-refresh-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::refresh, tick);
    }

    public void refresh() {
        for (var secret : secrets) {
            try {
                secret.refreshIfDue();
            } catch (RuntimeException ex) {
                LoggerFactory.getLogger(VaultRefreshTask.class)
                        .warn("Vault refresh failed for {}; retaining last value and retrying",
                                secret.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void destroy() { scheduler.shutdown(); }
}
