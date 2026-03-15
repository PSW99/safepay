package com.safepay.domain.account.controller;

import com.safepay.domain.account.dto.AccountDto.AccountResponse;
import com.safepay.domain.account.dto.AccountDto.CreateRequest;
import com.safepay.domain.account.dto.AccountDto.CreateResponse;
import com.safepay.domain.account.service.AccountService;
import com.safepay.global.security.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Account", description = "계좌 API")
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "계좌 개설")
    @PostMapping
    public ResponseEntity<CreateResponse> createAccount(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(principal.getMemberId(), request));
    }

    @Operation(summary = "내 계좌 목록 조회")
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getMyAccounts(
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ResponseEntity.ok(accountService.getMyAccounts(principal.getMemberId()));
    }

    @Operation(summary = "계좌 상세 조회")
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long accountId) {
        return ResponseEntity.ok(accountService.getAccount(accountId, principal.getMemberId()));
    }
}
