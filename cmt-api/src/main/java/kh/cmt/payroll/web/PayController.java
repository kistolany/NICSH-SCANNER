package kh.cmt.payroll.web;

import java.util.UUID;
import kh.cmt.payroll.application.PayCommands;
import kh.cmt.payroll.application.PayService;
import kh.cmt.payroll.application.PayView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Add / Edit person › Pay (needs "See salary &amp; bank details").
 * <pre>
 * GET  /api/v1/admin/people/{id}/pay   {current, upcoming, history (newest first, with old → new changes)}
 * POST /api/v1/admin/people/{id}/pay   new pay from effectiveFrom on (201, the whole history)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/people/{id}/pay")
class PayController {

    private final PayService service;

    PayController(PayService service) {
        this.service = service;
    }

    @GetMapping
    PayView.History history(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping
    ResponseEntity<PayView.History> setPay(@PathVariable UUID id, @RequestBody PayCommands.SetPay command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.setPay(id, command));
    }
}
