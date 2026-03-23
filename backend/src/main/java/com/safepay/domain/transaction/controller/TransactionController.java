package com.safepay.domain.transaction.controller;

import com.safepay.domain.transaction.dto.TransactionDto.DepositRequest;
import com.safepay.domain.transaction.dto.TransactionDto.TransactionResponse;
import com.safepay.domain.transaction.dto.TransactionDto.WithdrawRequest;
import com.safepay.domain.transaction.dto.TransferDto;
import com.safepay.domain.transaction.service.TransactionService;
import com.safepay.global.security.CustomUserPrincipal;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Transaction", description = "거래(입금/출금) API")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(summary = "입금")
    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long accountId,
            @Parameter(description = "멱등성 키 (UUID v4)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        validateIdempotencyKey(idempotencyKey);
        TransactionResponse response = transactionService.deposit(
                accountId, principal.getMemberId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "출금")
    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long accountId,
            @Parameter(description = "멱등성 키 (UUID v4)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request) {

        validateIdempotencyKey(idempotencyKey);
        TransactionResponse response = transactionService.withdraw(
                accountId, principal.getMemberId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    @Operation(summary = "거래 내역 조회")
    @GetMapping("/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long accountId,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(
                transactionService.getTransactions(accountId, principal.getMemberId(), pageable));
    }

    @Operation(summary = "송금")
    @PostMapping("/transfer")
    public ResponseEntity<TransferDto.TransferResponse> transfer(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable Long accountId,
            @Parameter(description = "멱등성 키 (UUID v4)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferDto.TransferRequest request) {

        validateIdempotencyKey(idempotencyKey);
        TransferDto.TransferResponse response = transactionService.transfer(
                accountId, request.getToAccountId(),
                principal.getMemberId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
