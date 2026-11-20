package dev.rendy.kirim.risk.api;

import dev.rendy.kirim.risk.rules.DailyActivity;
import dev.rendy.kirim.risk.rules.RiskProperties;
import dev.rendy.kirim.risk.store.Alert;
import dev.rendy.kirim.risk.store.RiskRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AlertController {

    public record DailyActivityResponse(UUID accountId, LocalDate day, long totalAmount, int transferCount) {
    }

    private final RiskRepository repository;
    private final ZoneId zone;
    private final Clock clock;

    public AlertController(RiskRepository repository, RiskProperties props, Clock clock) {
        this.repository = repository;
        this.zone = ZoneId.of(props.zone());
        this.clock = clock;
    }

    /** Newest alerts first; optionally for one account. */
    @GetMapping("/alerts")
    public List<Alert> alerts(@RequestParam(required = false) UUID accountId) {
        return accountId == null ? repository.findRecentAlerts() : repository.findAlertsByAccount(accountId);
    }

    /** What an account has sent on a business day (default: today in the configured zone). */
    @GetMapping("/accounts/{accountId}/daily-activity")
    public DailyActivityResponse dailyActivity(
            @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        LocalDate date = day != null ? day : LocalDate.now(clock.withZone(zone));
        DailyActivity activity = repository.findDaily(accountId, date).orElse(new DailyActivity(0, 0));
        return new DailyActivityResponse(accountId, date, activity.totalAmount(), activity.transferCount());
    }
}
