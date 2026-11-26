# А6. State-transition - карта, транзакция и резерв

**Чего не хватало:** диаграмм переходов и разграничения результата авторизации и состояния резерва.

**Что сделано:** построены переходы по методам модели и сервиса

## Card.status

```mermaid
stateDiagram-v2
    [*] --> Existing: createCard / status=ACTIVE
    state "Существующая неудалённая карта" as Existing {
        [*] --> ACTIVE
        ACTIVE --> INACTIVE: PATCH status=INACTIVE
        ACTIVE --> BLOCKED: PATCH status=BLOCKED
        ACTIVE --> EXPIRED: PATCH status=EXPIRED
        INACTIVE --> ACTIVE: PATCH status=ACTIVE
        INACTIVE --> BLOCKED: PATCH status=BLOCKED
        INACTIVE --> EXPIRED: PATCH status=EXPIRED
        BLOCKED --> ACTIVE: PATCH status=ACTIVE
        BLOCKED --> INACTIVE: PATCH status=INACTIVE
        BLOCKED --> EXPIRED: PATCH status=EXPIRED
        EXPIRED --> ACTIVE: PATCH status=ACTIVE
        EXPIRED --> INACTIVE: PATCH status=INACTIVE
        EXPIRED --> BLOCKED: PATCH status=BLOCKED
    }
    Existing --> DELETED: DELETE / soft delete
    DELETED --> [*]: обычный API больше не находит карту
    note right of Existing
        PATCH разрешает любой статус CardModelStatus,
        включая сохранение текущего значения.
        Условия валидности остальных полей остаются.
    end note
```

## Результат Transaction и состояние Reservation

```mermaid
stateDiagram-v2
    state "Обработка операции (логическая модель)" as Processing
    state "APPROVED: результат Authorization" as Approved
    state "DECLINED: результат Authorization" as Declined
    state "Компенсация резерва" as Compensation
    state "ROLLED_BACK: Reservation.status" as RolledBack
    state "Откат не подтверждён: резерв мог остаться" as Uncertain
    state "DECLINED/96: ответ клиенту" as ClientDecline
    [*] --> Processing: запрос авторизации
    Processing --> Declined: проверка не пройдена / без reserve
    Processing --> Approved: проверки пройдены / создать RESERVED
    Approved --> Compensation: ошибка публикации / HTTP rollback
    Compensation --> RolledBack: rollback успешен / вернуть amount на баланс
    Compensation --> Uncertain: rollback вернул отказ или null
    RolledBack --> ClientDecline: Switch возвращает systemError
    Uncertain --> ClientDecline: Switch возвращает systemError
    Approved --> [*]: публикация без синхронной ошибки / ответ APPROVED
    Declined --> [*]: попытка публикации / ответ DECLINED
    ClientDecline --> [*]
```

## Подтверждение кодом

| Элемент | Источник |
|---|---|
| Статусы и операции над картой | [CardStatus](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardStatus.java), [CardModelStatus](../../../services/common/src/main/java/com/processing/common/dto/cardmanagement/CardModelStatus.java) (без DELETED во входном DTO), [Card](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java), `withData`, `deleted`, `checkStatusOrThrow` |
| PATCH/DELETE/bulk и границы транзакций | [CardController](../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java), [CardServiceImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java) |
| Исключение удалённых карт | [CardEntity](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardEntity.java) |
| Проверка статуса и срока | [AuthServiceImpl](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [DeclineOutcome](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java) |
| Состояния резерва | [Reservation](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java), `startRollback`, `rolledBack` |
| Компенсация и ответ 96 | [RouteService](../../../services/switch/src/main/java/com/processing/service/RouteService.java), `route`, `logRollbackResult` |
