# А4. Sequence - одобрение, отказ и reversal

**Чего не хватало:** сценариев declined и reversal, исходная диаграмма покрывала только happy path.

**Что сделано:** разобраны одобрение, отказ для BLOCKED-карты и компенсация после ошибки публикации, разделены синхронные вызовы и асинхронная доставка

## 1. Happy path

```mermaid
sequenceDiagram
    participant T as Терминал / клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as Card-Management
    participant B as BIN Lookup
    participant P as PostgreSQL
    participant M as Merchant Acquirer
    participant Q as RabbitMQ
    participant L as Logger
    T->>G: HTTP POST /api/transactions, mti=0100
    Note over G,S: Валидация Gateway, затем BIN-routing Switch
    G->>S: HTTP POST /api/internal/route
    S->>A: HTTP POST /api/internal/authorize
    A->>C: HTTP GET /api/cards/{pan}
    C->>P: JDBC SELECT card
    P-->>C: ACTIVE, срок, баланс, лимиты
    C-->>A: CardModel
    A->>B: HTTP GET /api/bin/{bin}
    B-->>A: issuerId (только enrichment/log)
    Note over A: Статус → срок → баланс
    A->>P: JDBC upsert limit_usage, выделение RRN
    P-->>A: Успех
    A->>C: HTTP POST /api/cards/{pan}/reserve
    C->>P: JDBC lock, reservation=RESERVED, баланс минус amount
    P-->>C: Commit
    C-->>A: Карта с новым балансом
    A-->>S: APPROVED / 00
    S->>M: HTTP запрос комиссии
    M-->>S: Комиссия либо fallback клиента
    S-)Q: AMQP smp.transactions / transaction.log
    Note over S,Q: LoggerClient возвращает true,<br/>если нет AmqpException.<br/>Явного ожидания confirm нет
    par Ответ клиенту
        S-->>G: APPROVED / 00
        G-->>T: HTTP-ответ
    and Независимая доставка в Logger
        Q-)L: AMQP transaction-log
        L->>P: JDBC сохранить Transaction
        P-->>L: Запись сохранена
        Note over L: WebSocket broadcast через Dashboard nginx
    end
```

## 2. Declined: карта BLOCKED

```mermaid
sequenceDiagram
    participant T as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as Card-Management
    participant B as BIN Lookup
    participant M as Merchant Acquirer
    participant Q as RabbitMQ
    participant L as Logger
    T->>G: HTTP POST /api/transactions, mti=0100
    G->>S: HTTP POST /api/internal/route
    S->>A: HTTP POST /api/internal/authorize
    A->>C: HTTP GET card
    C-->>A: CardModel, status=BLOCKED
    A->>B: HTTP BIN enrichment
    B-->>A: Результат или отсутствие enrichment
    Note over A,C: Нет upsert limit_usage и нет reserve
    A-->>S: HTTP 403, DECLINED / 05 / CARD_BLOCKED
    S->>M: HTTP запрос комиссии
    M-->>S: Результат
    S-)Q: AMQP Transaction со статусом DECLINED
    par Ответ
        S-->>G: DECLINED / 05 / CARD_BLOCKED
        G-->>T: HTTP-ответ
    and Журнал
        Q-)L: AMQP transaction-log
        Note over L: Сохранение DECLINED через JDBC
    end
```

## 3. Reversal

```mermaid
sequenceDiagram
    participant T as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as Card-Management
    participant P as PostgreSQL
    participant B as BIN Lookup
    participant M as Merchant Acquirer
    participant Q as RabbitMQ
    T->>G: HTTP POST /api/transactions, mti=0100
    G->>S: HTTP POST /api/internal/route
    S->>A: HTTP authorize
    A->>C: HTTP GET /api/cards/{pan}
    C->>P: JDBC SELECT card
    P-->>C: ACTIVE, будущий срок, достаточно средств
    C-->>A: CardModel
    A->>B: HTTP GET /api/bin/{bin}
    B-->>A: Результат enrichment
    Note over A: Проверить статус, срок, баланс
    A->>P: JDBC upsert limit_usage, выделение RRN
    P-->>A: Успех, RRN=R
    A->>C: HTTP reserve, rrn=R, amount=X
    C->>P: JDBC RESERVED, баланс минус X
    P-->>C: Commit
    C-->>A: Успех
    A-->>S: APPROVED / 00, rrn=R
    S->>M: HTTP запрос комиссии
    M-->>S: Результат
    S-)Q: Попытка AMQP publish APPROVED
    Note over S,Q: Фактический триггер: AmqpException.<br/>LoggerClient.log=false
    Note over S,A: Требуемый reversal 0400:<br/>HTTP rollback без поля mti
    S->>A: HTTP POST /api/internal/rollback, R / PAN / X
    A->>C: HTTP POST /api/cards/{pan}/rollback
    C->>P: JDBC lock card/reservation, создать rollback
    C->>P: JDBC reservation=ROLLED_BACK, баланс плюс X
    P-->>C: Commit
    C-->>A: Карта с восстановленным балансом
    A-->>S: APPROVED / 00 (результат отката)
    Note over S: Результат rollback только логируется
    S-->>G: DECLINED / 96 (результат исходной операции)
    G-->>T: HTTP-ответ
```

## Подтверждение кодом

| Фрагмент | Источник |
|---|---|
| Вход и маршрутизация Gateway | [TransactionRequestValidator](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [application.yml](../../../services/gateway/src/main/resources/application.yml) |
| Оркестрация трёх сценариев, комиссия, публикация, rollback | [RouteService](../../../services/switch/src/main/java/com/processing/service/RouteService.java), `route`, [AuthorizationClient](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) |
| Порядок проверок, лимиты, reserve и rollback | [AuthServiceImpl](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [AuthControllerImpl](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java) |
| Фактический контракт отката | [RollbackRequest](../../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java), [CardManagementClientImpl](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java) |
| Резервирование и возврат средств | [CardServiceImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), `reserve`, `rollback` |
| Публикация и доставка | [LoggerClient](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [Switch config](../../../services/switch/src/main/resources/application.yml), [TransactionLogListener](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [TransactionService](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
