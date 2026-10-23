# А3. C4 Level 3 - Authorization и Card-Management

**Чего не хватало:** компонентных диаграмм Authorization и Card-Management с внутренними вызовами и внешними зависимостями.

**Что сделано:** показаны контроллеры, сервисы, HTTP-клиенты, репозитории и outbox-процессор, отмечены внутрипроцессные вызовы и границы HTTP, JDBC и AMQP.

## Authorization

```mermaid
flowchart LR
    Caller["Switch / HTTP-клиент теста"]
    subgraph AUTH["authorization"]
        Ctrl["AuthControllerImpl<br/>валидация, HTTP-коды"]
        Svc["AuthServiceImpl<br/>статус → срок → баланс → лимиты → reserve"]
        CMS["CardManagementClientImpl"]
        BIN["BinLookupClient"]
        Repo["LimitUsageRepository<br/>native SQL upsert, rrn_seq"]
        Events["AuthorizationEventNotifier<br/>локальные listeners"]
    end
    C["Card-Management"]
    B["BIN Lookup"]
    DB[("PostgreSQL")]
    Caller -->|"sync HTTP: authorize / rollback"| Ctrl
    Ctrl -->|"in-process"| Svc
    Svc -->|"in-process: getCard, reserve, rollback"| CMS
    CMS -->|"sync HTTP"| C
    Svc -->|"in-process: getIssuerId"| BIN
    BIN -->|"sync HTTP: /api/bin/{bin}"| B
    Svc -->|"in-process"| Repo
    Repo -->|"sync JDBC/JPA"| DB
    Svc -->|"in-process: диагностические события"| Events
```

## Card-Management

```mermaid
flowchart TB
    Caller["Gateway / Authorization / HTTP-клиент теста"]
    subgraph CMS["card-management"]
        Ctrl["CardController + CardGeneratorController"]
        Gen["CardGeneratorService"]
        Svc["CardServiceImpl"]
        Domain["Card / Reservation<br/>withData, startReservation, startRollback"]
        Tx["TransactionRunner<br/>границы транзакций"]
        Helpers["PanGenerator / BinIssuerService / RetryService"]
        Repo["CardRepository / ReservationRepository /<br/>ReservationRollbackRepository / BinIssuerRepository<br/>JPA persistence adapters"]
        Notify["CardEventNotifierImpl"]
        Local["Локальные CardEventListener"]
        Proc["OutboxEventProcessorImpl<br/>save / processSingleEvent"]
        Scheduler["OutboxProcessor<br/>scheduled process"]
        OutRepo["OutboxRepository → OutboxJpaAdapter"]
        Rabbit["RabbitTemplate"]
    end
    DB[("PostgreSQL<br/>cards, reservations, rollbacks, outbox")]
    MQ["RabbitMQ<br/>smp.card-events"]
    Caller -->|"sync HTTP: CRUD / generate / reserve / rollback"| Ctrl
    Ctrl -->|"in-process"| Svc
    Ctrl -->|"in-process: generate"| Gen
    Gen -->|"in-process: createCards"| Svc
    Svc -->|"in-process"| Domain
    Svc -->|"in-process"| Tx
    Svc -->|"in-process"| Helpers
    Helpers -->|"in-process: BIN-справочник"| Repo
    Svc -->|"in-process"| Repo
    Repo -->|"sync JDBC/JPA"| DB
    Svc -->|"in-process: event"| Notify
    Notify -->|"in-process: обычный CardEvent"| Local
    Notify -->|"in-process: CardOutboxEvent"| Proc
    Proc -->|"in-process: save"| OutRepo
    Scheduler -->|"in-process: findPending"| OutRepo
    Scheduler -->|"in-process: processSingleEvent"| Proc
    OutRepo -->|"sync JDBC/JPA"| DB
    Proc -->|"in-process: convertAndSend"| Rabbit
    Rabbit -.->|"async AMQP: card.EventClass"| MQ
```

| Компонент / связь | Подтверждение |
|---|---|
| HTTP → бизнес-логика | [CardController](../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java), [CardGeneratorService](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardGeneratorService.java) |
| Домен и транзакции | [CardServiceImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [Card](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java), [Reservation](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java), [TransactionRunner](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunner.java) |
| Доступ к PostgreSQL | [CardRepositoryPersistenceAdapter](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardRepositoryPersistenceAdapter.java), [CardJpaRepository](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java), [BinIssuerServiceImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/BinIssuerServiceImpl.java) |
| Событие → запись outbox | [CardEventNotifierImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java), [OutboxJpaAdapter](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/OutboxJpaAdapter.java) |
| Чтение outbox → публикация | [OutboxProcessor](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [OutboxEventProcessorImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [RabbitMQConfig](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java) |

Не все изменения автоматически дают одно событие: публикуются только классы, реализующие `CardOutboxEvent`. Например, [CardServiceCreationEvent](../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardServiceCreationEvent.java) содержит количество созданных карт, а не PAN

