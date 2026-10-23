# А5. RabbitMQ - топология и границы гарантий

**Чего не хватало:** визуальной карты exchange → queue → consumer и различения publisher-confirm, повторов consumer, повторов outbox и TTL.

**Что сделано:** нанесены имена exchange/queue, routing keys, DLX/DLQ и параметры из кода. Пунктир - async AMQP, сплошные стрелки внутри брокера - маршрутизация. JDBC-переходы подписаны отдельно.

```mermaid
flowchart TB
    S["Switch: LoggerClient"]
    O["CMS: OutboxProcessor<br/>раз в 1000 ms по умолчанию"]
    OP[("PostgreSQL: outbox<br/>PENDING / PROCESSED / FAILED")]
    subgraph R["RabbitMQ"]
        TX["smp.transactions<br/>durable topic exchange"]
        TQ["transaction-log<br/>durable queue<br/>x-max-delivery = 3"]
        DX["smp.transactions.dlx<br/>durable direct exchange"]
        DQ["transaction-log-dlq<br/>TTL = 60000 ms"]
        CX["smp.card-events<br/>durable topic exchange"]
        CQ["card-notifications<br/>durable queue<br/>x-max-delivery = 3"]
        CDX["smp.card-events.dlx<br/>durable direct exchange"]
        CDQ["card-notifications-dlq<br/>TTL = 60000 ms"]
        TX -->|"binding: transaction.log"| TQ
        TQ -->|"dead-letter при reject/expiry и др., key=transaction-log"| DX
        DX -->|"binding: transaction-log"| DQ
        CX -->|"binding: card.*"| CQ
        CQ -->|"dead-letter при reject/expiry и др., key=card-notifications"| CDX
        CDX -->|"binding: card-notifications"| CDQ
    end
    L["TransactionLogListener<br/>→ TransactionService.store"]
    N["CardEventListener<br/>→ CardNotificationRepository"]
    DB[("PostgreSQL<br/>transactions / card_notifications")]
    S -.->|"async AMQP: transaction.log"| TX
    O -->|"sync JDBC/JPA: findPending / save"| OP
    O -.->|"async AMQP: card. + simpleName класса события"| CX
    TQ -.->|"async AMQP delivery"| L
    CQ -.->|"async AMQP delivery"| N
    L -->|"sync JDBC/JPA"| DB
    N -->|"sync JDBC/JPA"| DB
```

## Подтверждение кодом

| Элемент | Источник |
|---|---|
| Полная transaction-топология, включая DLX/DLQ | [Switch RabbitMQConfig](../../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java) |
| Повторное объявление основного transaction exchange/queue | [Logger RabbitMQConfig](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/config/RabbitMQConfig.java), этот класс сам DLX/DLQ не объявляет |
| Полная card-топология, `card.*` | [CMS RabbitMQConfig](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java), [Notification RabbitMQConfig](../../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java) |
| Поведение producer Switch | [LoggerClient](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [application.yml](../../../services/switch/src/main/resources/application.yml) |
| Поведение producer CMS и счётчик повторов | [OutboxEventProcessorImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [OutboxProcessor](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [CardOutboxEventData](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardOutboxEventData.java), [application.properties](../../../services/card-management/src/main/resources/application.properties) |
| Consumer Logger и его конфигурация | [TransactionLogListener](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [application.yaml](../../../services/transaction-logger/src/main/resources/application.yaml) |
| Consumer Notification и его конфигурация | [CardEventListener](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java), [application.yml](../../../services/notification-service/src/main/resources/application.yml) |

