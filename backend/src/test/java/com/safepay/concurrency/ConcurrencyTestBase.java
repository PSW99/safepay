package com.safepay.concurrency;

import com.safepay.integration.IntegrationTestBase;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ConcurrencyTestBase extends IntegrationTestBase {
}
