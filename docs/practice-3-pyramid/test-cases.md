# Практика 3 - тест-кейсы и анализ пирамиды

## Основание и границы проверок

| Уровень | Граница теста |
|---|---|
| unit | Реальный метод/класс, внешние зависимости заменены stub/mock, без сети и БД |
| api | Реальные HTTP, валидация и сериализация, предмет проверки - контракт |
| integration | Реальная связь сервиса с PostgreSQL, RabbitMQ или другим сервисом |
| e2e | Gateway → Switch → Authorization → CMS и RabbitMQ → Logger, зависимости контура настоящие |

### Общие данные

- Денежные значения - копейки. Для новых денежных сценариев исходно `amount=1000`, `availableBalance=20000`, `dailyLimit=50000`, `monthlyLimit=100000`, usage=0. Карта ACTIVE, срок в будущем, PAN берётся из созданной карты с BIN `400000`. В unit достаточно соответствующего CardModel/объекта домена.
- Валидный запрос `V`: `mti=0100`, уникальный шестизначный STAN, PAN карты, `processingCode=000000`, `amount=1000`, `currencyCode=643`, время операции `2026-10-23T10:00:00Z`, `terminalId=TERM0001`, `merchantId=MERCH0000000001`, `mcc=5411`, `acquirerId=ACQ001`, `terminalType=POS`. Срок карты позже октября 2026. Для E2E терминал и мерчант предварительно зарегистрированы в тестовых данных. Новые запуски получают уникальные STAN/RRN и изолированные карты.
- Для прямых запросов к внутреннему Authorization endpoint добавить `issuerId=ISS001`: DTO требует непустое значение. В запросах через Gateway его устанавливает Switch по BIN. Для unit Gateway можно использовать синтетический PAN `4000001234560001`: его валидатор проверяет 16 цифр, а не Luhn. Этот PAN не заменяет созданную карту интеграционного теста.
- В асинхронных сценариях ожидать **условие**, опрашивая каждые 100 ms не более 15 s (пример из лекции 5, слайд 6). Отсутствие записи сразу после ответа допустимо. Исключения: отдельные тесты 2s confirm и 60s TTL имеют собственные интервалы.
- Перед каждым интеграционным кейсом фиксировать исходные строки cards/reservations/limit_usage/outbox/notifications в его области. В конце очистить только тестовые данные. Не сравнивать глобальное количество строк при параллельной работе симуляторов.
- Позитивные сценарии выполняются перед соответствующими негативными. Если меняется один невалидный класс, остальные условия остаются валидными.

### Требования для новых кейсов

| ID | Требование / основание |
|---|---|
| GW-01 | Валидатор Gateway принимает `0100`, PAN из 16 цифр, amount > 0, остальные обязательные поля заполнены - [валидатор][gw] |
| SW-01 | Первые 6 цифр PAN определяют issuer по BIN-таблице, неизвестный BIN отклоняется - [RoutingService][routing] |
| SW-02 | Ошибка публикации после APPROVED инициирует rollback, после DECLINED не инициирует - [RouteService][route] |
| MQ-01 | Сообщение `transaction.log` доставляется в Logger, повтор идентичного id не создаёт вторую запись - [Logger][logger] |
| MQ-02 | Publisher-confirm ожидается до 2s, при отсутствии подтверждения исходная операция откатывается - **требование А4/А5 задания**, реализация расходится, см. [А4](architecture/04-sequences.md) |
| MQ-03 | После 3 неуспешных попыток обработки сообщение должно попасть в DLQ - **требование А5 задания**, фактическая гарантия требует проверки |
| MQ-04 | TTL сообщений в DLQ составляет 60000 ms - [RabbitMQConfig][mq] |
| CM-09 | Повтор отката одного резерва не возвращает средства повторно - [Reservation][reservation], [CardServiceImpl][cms] |
| CM-10 | Карта и её событие outbox должны сохраняться согласованно - требование сохранности события CM-08, атомарность `createCard` по коду не подтверждена |
| CM-11 | `Card.withData` отвергает monthlyLimit < dailyLimit - [Card][card], это инвариант обновления, его нельзя переносить на createCard без отдельной проверки |

## Часть (б). Новые тест-кейсы

### Unit - логика в изоляции

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-U-01 | GW-01 | unit | позитивный | TransactionRequestValidator, V, amount=1 | Вызвать validate(V) | Исключения нет, минимальная положительная целая сумма принята | Граничное значение: 1, [валидатор][gw] |
| P3-U-02 | GW-01 | unit | негативный | Как U-01, изменить только mti на 0400 | Вызвать validate | TransactionValidationException с указанием mti, публичный путь авторизации не принимает reversal | Класс эквивалентности: другой MTI, [валидатор][gw] |
| P3-U-03 | SW-01 | unit | позитивный | RoutingService с таблицей 400000 → ISS001 | getIssuerIdByPan для двух PAN с общими первыми 6 цифрами и разными хвостами | Для обоих ISS001, хвост PAN не влияет на маршрут | Класс эквивалентности: известный BIN, [RoutingService][routing] |
| P3-U-04 | SW-01 | unit | негативный | Таблица как U-03, PAN=4999991234567890 | Вызвать getIssuerIdByPan | UnknownBinException, отсутствует маршрут для 499999 | Класс эквивалентности: неизвестный BIN, [RoutingService][routing] |
| P3-U-05 | CM-11 | unit | негативный | Реальный Card, daily=1000, monthly=2000, balance=20000 | Вызвать withData(ACTIVE, 1000, 999, 20000) | IllegalArgumentException, исходный неизменяемый объект сохранил значения | Граничное значение: monthly=daily−1, [Card][card] |
| P3-U-06 | CM-08 | unit | негативный, переход состояния | OutboxEventProcessorImpl, maxRetryCount=3, RabbitTemplate всегда бросает AmqpException, репозиторий mock, событие PENDING/retryCount=0 | Трижды обработать последнее сохранённое состояние события | После ошибок 1/2: PENDING, счётчики 1/2, после 3: FAILED, retryCount=3, lastError заполнен, ни разу PROCESSED | Сценарий отказа и граница числа попыток, [outbox][outbox] |
| P3-U-07 | SW-02 | unit | негативный, компенсация | RouteService, authorize stub → APPROVED/00 с RRN, log stub → false, rollback stub → APPROVED/00, комиссия задана | route(V), проверить взаимодействия | Ровно один rollback с исходными PAN/amount и RRN авторизации, клиенту DECLINED/96 | Сценарий: publish failure после успеха, [RouteService][route] |
| P3-U-08 | SW-02 | unit | негативный | RouteService, authorize stub → DECLINED/05/CARD_BLOCKED, log → false | route(V), проверить взаимодействия | Исходный DECLINED/05 сохранён, rollback не вызван | Сценарий: publish failure после отказа, [RouteService][route] |

В U-06 проверяется алгоритм счётчика, а не доставка RabbitMQ. U-07 проверяет решение вызвать компенсацию, а не реальный возврат денег. Это позволяет оставить такие проверки на unit-уровне.

### API - контракты

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-A-01 | CM-09 | api | позитивный | HTTP-слой Authorization, AuthService stub возвращает RollbackResponse APPROVED/00 для RRN=123456789012, БД не нужна | POST /api/internal/rollback с rrn, pan валидной формы, amount=1000 | HTTP 200, status=APPROVED, responseCode=00, тот же rrn, входной контракт не требует mti | Контракт: [контроллер][auth-controller], [RollbackRequest][rollback] |
| P3-A-02 | AUTH-02 | api | негативный | HTTP-слой Authorization, AuthService stub возвращает CARD_BLOCKED/05 | POST /api/internal/authorize с V | HTTP 403, status=DECLINED, responseCode=05, declineReason=CARD_BLOCKED, проверяется преобразование ответа сервиса, а не алгоритм статуса | Контракт: [AuthControllerImpl][auth-controller] |
| P3-A-03 | GW-01 | api | негативный | Реальный HTTP-слой Gateway, downstream Switch - HTTP stub со счётчиком запросов | POST /api/transactions с V и mti=0400 | HTTP 400, Switch не получил запрос, ошибка не превращается в обычную оплату | Контракт и невалидный класс MTI, [валидатор][gw] |

U-02 и A-03 проверяют разные результаты: исключение метода и HTTP-ответ с остановкой маршрутизации. Успешный API-контракт не доказывает сохранение резерва или карты в БД.

### Integration - SQL, очереди, компенсация

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-I-01 | AUTH-04,05 | integration | негативный, конкуренция | Реальный LimitUsageRepository + PostgreSQL, на дату операции daily_amount=9000, monthly_amount=9000, dailyLimit=10000, monthlyLimit=50000 | Из двух независимых транзакций одновременно вызвать upsertLimitUsage(amount=1000) для одного PAN/дня, дождаться обеих | Один вызов обновил строку, другой вернул 0, итог daily_amount=10000, monthly_amount=10000, превышения нет | Состояние БД и граница остатка, [SQL лимитов][limits] |
| P3-I-02 | CM-07 | integration | негативный, конкуренция | CMS + PostgreSQL, ACTIVE, balance=1000, два новых RRN | Одновременно POST /api/cards/{pan}/reserve по 1000 с разными RRN | Один HTTP 200, другой 402, баланс=0, один резерв, отрицательного остатка нет | Сценарий конкурентного резервирования, [CardServiceImpl][cms] |
| P3-I-03 | MQ-01 | integration | позитивный, повтор доставки | RabbitMQ, Logger, PostgreSQL, очередь очищена в изолированном стенде, валидный TransactionRequest с новым UUID/STAN | Опубликовать идентичный DTO дважды в smp.transactions с key=transaction.log, дождаться обработки обеих доставок и записи по UUID | В БД ровно одна транзакция с исходными полями, повтор не создаёт вторую запись | Контракт сообщения и состояние БД, [TransactionService][logger] |
| P3-I-04 | CM-08 | integration | позитивный | CMS, PostgreSQL, RabbitMQ, Notification, карта создана, её предыдущие события обработаны | PATCH карты в BLOCKED, найти новый outbox по событию и PAN, ждать уведомление с routingKey=card.CardServicePatchEvent и PAN в payload | Статус карты сохранён, outbox PROCESSED, Notification сохранил соответствующий payload, совпадают тип и PAN | Сценарий outbox → Notification, [outbox][outbox], [consumer][notification] |
| P3-I-05 | CM-08 | integration | позитивный, восстановление | Те же компоненты, scheduler в тесте отключён, для PAN есть одно PENDING patch-событие, RabbitMQ недоступен | Вручную вызвать один цикл OutboxProcessor.process, проверить БД, восстановить брокер до следующего цикла, повторить process, ждать Notification | После отказа PENDING/retryCount=1 и lastError, после восстановления PROCESSED и уведомление с этим PAN. Карта не потеряна | Сценарий восстановления до исчерпания retry, [OutboxProcessor][scheduler] |
| P3-I-06 | MQ-02, SW-02 | integration | негативный, проверка требования | Switch/LoggerClient + настоящий RabbitMQ через управляемый AMQP-прокси, Authorization HTTP stub → APPROVED с RRN, rollback stub считает вызовы, Merchant stub доступен | Пропустить публикацию брокеру, но удержать publisher basic.ack/basic.nack более 2s, сохраняя соединение, вызвать route(V), отслеживать время от отправки, затем освободить прокси | По заданию до положительного confirm нельзя вернуть APPROVED, после истечения 2s должен начаться rollback с исходным RRN, итог DECLINED/96. Для измерения допускается до 0,5s накладных расходов стенда. Текущий LoggerClient этого ожидания явно не реализует | Сценарий отсутствующего confirm, требование А4/А5, [LoggerClient][publisher] |
| P3-I-07 | MQ-03 | integration | негативный, проверка требования | RabbitMQ + Notification с конфигурацией проекта, изолированные card-notifications/DLQ, доступно наблюдение числа вызовов listener, БД исправна | Опубликовать некорректный JSON с key=card.CardServicePatchEvent, считать попытки обработки до 15s, проверить DLQ | По заданию ровно 3 попытки, затем одно сообщение в card-notifications-dlq, дальнейшая обработка прекращена. Бесконечный повтор либо иное число попыток - расхождение, а не успешный результат | Невалидный класс сообщения и сценарий retry, [топология][mq], [consumer][notification] |
| P3-I-08 | MQ-04 | integration | позитивный, граница TTL | Топология объявлена, card-notifications-dlq пуста, consumer DLQ отсутствует | Опубликовать уникальное сообщение напрямую в smp.card-events.dlx с key=card-notifications, подтвердить наличие без удаления до 5s, опрашивать размер очереди после 60s до 70s | Сообщение сначала присутствует, после TTL исчезает, обратно в card-notifications не доставляется. Время физического удаления не проверяется с точностью до миллисекунды | Граничное значение времени хранения 60000 ms, [RabbitMQConfig][mq] |
| P3-I-09 | CM-09 | integration | негативный, повтор отката | CMS + PostgreSQL, успешный reserve=1000, исходный balance=20000, новый RRN | POST /api/cards/{pan}/rollback дважды с одним RRN и amount=1000 | Первый запрос 200, второй 409, итог balance=20000, reservation=ROLLED_BACK, одна запись возврата | Переход состояния и состояние БД, [Reservation][reservation], [CardServiceImpl][cms] |
| P3-I-10 | CM-10 | integration | негативный, атомарность | CMS + PostgreSQL, зарегистрированный BIN, уникальный набор карты, тестовая ошибка сохранения outbox при исправном сохранении cards | Вызвать createCard через сервис с реальным CardRepository и OutboxRepository, который на insert выбрасывает исключение, проверить обе таблицы после завершения | По требованию атомарности нет частичного результата: ни новой карты, ни outbox. По текущему createCard общая транзакция не видна, сохранённая карта без события фиксируется как дефект, не как ожидаемый успех | Состояние БД при сбое между двумя записями, CM-08 и [CardServiceImpl][cms] |
| P3-I-11 | AUTH-07, CM-07 | integration | негативный, согласованность | Authorization + PostgreSQL, CMS HTTP stub: GET возвращает валидную карту, reserve отвечает 503, BIN stub доступен, usage=0 | Авторизовать V, после ответа перечитать limit_usage независимой транзакцией | DECLINED, успешного резерва нет, usage должен остаться 0 по инварианту отказа из практики 2. В коде upsert стоит до reserve, а исключение перехватывается - возможен дефект сохранения usage | Состояние БД при отказе зависимости, [AuthServiceImpl][auth], [LimitUsageRepository][limits] |

I-06 требует стенда, умеющего задерживать именно подтверждение AMQP, а не просто отключать весь брокер. Подмена `log=false` уже проверена U-07 и не заменяет этот сценарий. I-07 проверяет требование о числе повторов, наличие `x-max-delivery=3` само по себе не считается результатом теста. I-10 использует управляемый отказ только outbox-операции: настоящая транзакция сохранения карты обязательна, иначе атомарность не проверяется.

### E2E - связность контура

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-E-01 | AUTH-07, CM-07, MQ-01 | e2e | позитивный | Полный бизнес-контур, данные V, исходные balance=20000 и usage=0, BIN известен | POST V через Gateway, сохранить STAN/RRN, ожидать запись Logger по STAN, GET карту, проверить резерв и usage | Ответ APPROVED/00, balance=19000, один резерв на 1000, usage увеличен на 1000, Logger в пределах 15s содержит соответствующие STAN/RRN, amount=1000, APPROVED | Сквозной сценарий и eventual consistency, [А4](architecture/04-sequences.md) |
| P3-E-02 | AUTH-02, CM-04, MQ-01 | e2e | позитивный и негативный переходы | Полный контур, отдельная ACTIVE-карта, balance=20000, срок в будущем | Через Gateway PATCH status=BLOCKED, отправить V со STAN1, дождаться Logger, PATCH status=ACTIVE, отправить V со STAN2, дождаться Logger | Первая операция DECLINED/05/CARD_BLOCKED, баланс и usage не меняются, вторая APPROVED/00, баланс=19000, в журнале две соответствующие записи, резерв только для второй операции | Сценарий переходов ACTIVE → BLOCKED → ACTIVE, [А6](architecture/06-states.md) |

Здесь E2E проходит через публичный HTTP API без обязательного браузера: проверяется связность бизнес-процесса, а не вёрстка. Для редкого отказа брокера достаточно U-07 плюс I-06/I-09, добавлять ещё один дорогой полный прогон только ради процента E2E не требуется.

## Часть (в). Распределение тест-кейсов практики 2

Источник - [test-design.md](../practice-2/test-design.md). Сохраняются все существующие ID, включая ручной `TC-A-CRIT-01` из текста раздела 5.2. Диапазоны BVA раскрыты в отдельные строки, пропущенные в оригинале номера EQ не придуманы. `TC-CM-BVA-10` остаётся одним параметризованным кейсом формата MMYY, значения его набора отдельно не увеличивают счётчик.

**Сохранение смысла исходных тестов:** у Authorization BVA и pairwise явно проверяются баланс и `limit_usage`, у Card-Management pairwise - отсутствие/наличие карты и outbox. Поэтому эти тесты остаются integration даже для ранних отказов. Для EQ применяется общая проверка побочных эффектов из исходного раздела 4. Назвать их unit и заменить реальные изменения БД проверкой вызовов mock означало бы сузить исходную проверку.

Card-Management BVA, которые проверяют только формат, HTTP-код и схему, отнесены к api с контролируемым сервисом, проверки реального остатка reserve - integration. У MMYY нет входного поля create/PATCH: формат проверяется непосредственно в [CardExpiryDateMapper](../../services/card-management/src/main/java/com/processing/cardmanagement/mappers/CardExpiryDateMapper.java), поэтому для этого исходного кейса выбран unit и явно указана точка применения.

### Наблюдения об исходных ожиданиях

- В [AuthServiceImpl][auth] баланс проверяется **до** лимитов, а старый S-AUTH задаёт обратный порядок. Поэтому PW-A-14/PW-A-19 при одновременном превышении могут дать 51 вместо ожидаемого 61. Старые ожидания здесь не переписаны молча: сначала нужно согласовать их с требованием.
- Старый TC-A-EQ-09 ожидает `05/ISSUER_TIMEOUT`. Код при недоступном CMS формирует `96/SERVICE_UNAVAILABLE` ([DeclineOutcome](../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java), [AuthorizationResponse](../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationResponse.java)). Это отдельное расхождение контракта.
- Нулевая сумма: DTO Authorization допускает PositiveOrZero, Gateway требует > 0, а reserve CMS требует положительную сумму. Принятие DTO не гарантирует успешную авторизацию с нулём. Старые TC-A-EQ-05 и TC-A-BVA-17 нужно исполнять через внутренний Authorization endpoint с раздельной оценкой валидации и бизнес-результата.
- Отсутствие API-контракта на конкретную операцию не маскируется вымышленным endpoint. Это особенно существенно для MMYY и для reversal 0400.

Ниже каждый ID учтён ровно один раз, «Название» кратко обозначает проверку, поскольку отдельной колонки названий в оригинале нет.

### Реестр старых кейсов

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| PW-A-01 | Authorization pairwise, строка 1 | DECLINED/54 CARD_EXPIRED по статусу, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-02 | Authorization pairwise, строка 2 | DECLINED/05 CARD_BLOCKED, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-03 | Authorization pairwise, строка 3 | DECLINED/05 CARD_INACTIVE. Статус раньше expiry, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-04 | Authorization pairwise, строка 4 | DECLINED/05 CARD_INACTIVE, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-05 | Authorization pairwise, строка 5 | DECLINED/54 CARD_EXPIRED по статусу, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-06 | Authorization pairwise, строка 6 | DECLINED/05 CARD_BLOCKED, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-07 | Authorization pairwise, строка 7 | DECLINED/54 CARD_EXPIRED по статусу, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-08 | Authorization pairwise, строка 8 | DECLINED/54 CARD_EXPIRED по статусу, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-09 | Authorization pairwise, строка 9 | DECLINED/61 EXCEEDS_AMOUNT_LIMIT, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-10 | Authorization pairwise, строка 10 | DECLINED/61 EXCEEDS_AMOUNT_LIMIT, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-11 | Authorization pairwise, строка 11 | DECLINED/54 CARD_EXPIRED, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-12 | Authorization pairwise, строка 12 | DECLINED/61 EXCEEDS_AMOUNT_LIMIT, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-13 | Authorization pairwise, строка 13 | DECLINED/51 INSUFFICIENT_FUNDS, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-14 | Authorization pairwise, строка 14 | DECLINED/61. Daily имеет приоритет над monthly и balance, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-15 | Authorization pairwise, строка 15 | DECLINED/05 CARD_INACTIVE, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-16 | Authorization pairwise, строка 16 | DECLINED/61 EXCEEDS_AMOUNT_LIMIT, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-17 | Authorization pairwise, строка 17 | DECLINED/54. Expiry раньше monthly limit, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-18 | Authorization pairwise, строка 18 | DECLINED/05 CARD_BLOCKED, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-19 | Authorization pairwise, строка 19 | DECLINED/61. Daily раньше balance, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-20 | Authorization pairwise, строка 20 | APPROVED/00. Равенства допустимы, reserve один раз, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-21 | Authorization pairwise, строка 21 | DECLINED/54. Expiry раньше daily limit, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-22 | Authorization pairwise, строка 22 | DECLINED/05 CARD_BLOCKED, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-23 | Authorization pairwise, строка 23 | DECLINED/51 INSUFFICIENT_FUNDS, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-24 | Authorization pairwise, строка 24 | APPROVED/00. Reserve один раз, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-A-25 | Authorization pairwise, строка 25 | DECLINED/05 CARD_INACTIVE, reserve, баланс, limit_usage по S-AUTH | integration | Сохраняется вся проверка S-AUTH с реальной БД, изолированная ветвь authorize была бы более узким тестом |
| PW-C-01 | Card-Management pairwise, строка 1 | 400 currencyCode не 3 цифры, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-02 | Card-Management pairwise, строка 2 | 201. Нулевые значения допустимы, событие PENDING/retry, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-03 | Card-Management pairwise, строка 3 | 201. `monthly < daily`, событие PENDING/retry, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-04 | Card-Management pairwise, строка 4 | 400 неверный формат BIN, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-05 | Card-Management pairwise, строка 5 | 400 отрицательный dailyLimit, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-06 | Card-Management pairwise, строка 6 | 400 имя обязательно, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-07 | Card-Management pairwise, строка 7 | 400 отрицательный monthlyLimit, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-08 | Card-Management pairwise, строка 8 | 400 отрицательный initialBalance, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-09 | Card-Management pairwise, строка 9 | 201 карта сохранена, событие PENDING/retry, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-10 | Card-Management pairwise, строка 10 | 400 имя обязательно, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-11 | Card-Management pairwise, строка 11 | 400 неверный формат BIN, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-12 | Card-Management pairwise, строка 12 | 400 отрицательный dailyLimit, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-13 | Card-Management pairwise, строка 13 | 400 неверный формат BIN, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-14 | Card-Management pairwise, строка 14 | 400 имя обязательно, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-15 | Card-Management pairwise, строка 15 | 400 недопустимые символы имени, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-16 | Card-Management pairwise, строка 16 | 404 BIN_NOT_FOUND. Карта не создана, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-17 | Card-Management pairwise, строка 17 | 400 отрицательный initialBalance, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-18 | Card-Management pairwise, строка 18 | 400 currencyCode не 3 цифры, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-19 | Card-Management pairwise, строка 19 | 400 отрицательный monthlyLimit, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-20 | Card-Management pairwise, строка 20 | 404 BIN_NOT_FOUND. Карта не создана, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-21 | Card-Management pairwise, строка 21 | 400 недопустимые символы имени, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-22 | Card-Management pairwise, строка 22 | 404 BIN_NOT_FOUND. Карта не создана, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-23 | Card-Management pairwise, строка 23 | 400 отрицательный dailyLimit, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-24 | Card-Management pairwise, строка 24 | 400 currencyCode не 3 цифры, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-25 | Card-Management pairwise, строка 25 | 400 недопустимые символы имени, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| PW-C-26 | Card-Management pairwise, строка 26 | 400 отрицательный initialBalance, наличие/отсутствие карты и outbox по S-CM | integration | S-CM требует PostgreSQL и проверку outbox, включая отсутствие записей при отказе |
| TC-A-BVA-01 | Дневной остаток: 9999 | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-02 | Дневной остаток: 10000 | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-03 | Дневной остаток: 10001 | Ответ 61, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-04 | Месячный остаток: 49999 | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-05 | Месячный остаток: 50000 | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-06 | Месячный остаток: 50001 | Ответ 61, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-07 | Баланс: 19999 | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-08 | Баланс: 20000 | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-09 | Баланс: 20001 | Ответ 51, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-10 | Срок карты: предыдущий месяц | Ответ 54, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-11 | Срок карты: текущий месяц | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-12 | Срок карты: следующий месяц | Ответ 00, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-13 | Длина PAN: 15 | Ответ HTTP 400, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-14 | Длина PAN: 16 | Ответ бизнес-проверка, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-15 | Длина PAN: 17 | Ответ HTTP 400, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-16 | Минимальная сумма: −1 | Ответ HTTP 400, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-17 | Минимальная сумма: 0 | Ответ допустимо для DTO, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-BVA-18 | Минимальная сумма: 1 | Ответ допустимо для DTO, баланс и limit_usage после запроса, отсутствие изменений при отказе | integration | Исходный BVA требует чтения реального состояния БД, HTTP-контракта или mock недостаточно |
| TC-A-CRIT-01 | Сумма равна трём остаткам | APPROVED/00 и одно резервирование при daily=monthly=balance=equal | integration | Ручной сценарий из раздела 5.2 применяет S-AUTH: реальные лимиты, баланс и reserve |
| TC-A-EQ-01 | Авторизовать 1 000 | `APPROVED/00`, один reserve, валидные RRN/authCode, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-02 | Авторизовать с 16-значным неизвестным PAN | `DECLINED/14`, `CARD_NOT_FOUND`, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-03 | Передать PAN из 16 букв | HTTP 400, бизнес-логика не вызвана, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-04 | Передать пустой PAN | HTTP 400, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-05 | Авторизовать amount=0 | Запрос проходит в соответствии с контрактом `PositiveOrZero`, баланс не становится отрицательным, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-06 | Авторизовать amount=-1 | HTTP 400, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-08 | Передать MCC длиной 3 | HTTP 400, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-A-EQ-09 | Отправить валидную авторизацию | `DECLINED/05`, причина `ISSUER_TIMEOUT`. Деньги и лимиты не изменены, побочные эффекты по разделу 4 | integration | Реальный отказ CMS и отсутствие изменения баланса/usage - взаимодействие сервисов и БД |
| TC-CM-BVA-01 | Длина BIN: 5 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-02 | Длина BIN: 6 зарегистрированных | HTTP 201 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-03 | Длина BIN: 7 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-04 | Длина currencyCode: 2 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-05 | Длина currencyCode: 3 цифры | HTTP 201 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-06 | Длина currencyCode: 4 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-07 | Limit/balance: −1 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-08 | Limit/balance: 0 | HTTP 201/200 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-09 | Limit/balance: 1 | HTTP 201/200 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-10 | Формат MMYY | Разбор 0129/1229 и отказ для 0029/1329/129 | unit | CardExpiryDateMapper.asYearMonth - чистое преобразование, API не принимает expiryDate при создании/обновлении |
| TC-CM-BVA-11 | Длина PAN path: 15 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-12 | Длина PAN path: 16 | HTTP 200/404 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-13 | Длина PAN path: 17 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-14 | Длина RRN: 11 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-15 | Длина RRN: 12 | HTTP 200 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-16 | Длина RRN: 13 | HTTP 400 и формат входа/ответа | api | Предмет строки BVA - контракт HTTP, нижележащий сервис задаёт контролируемый результат |
| TC-CM-BVA-17 | Reserve: amount=19999 | Резерв и реальный остаток: баланс=1 | integration | Запрет отрицательного баланса проверяется вместе с записью резерва в PostgreSQL |
| TC-CM-BVA-18 | Reserve: amount=20000 | Резерв и реальный остаток: баланс=0 | integration | Запрет отрицательного баланса проверяется вместе с записью резерва в PostgreSQL |
| TC-CM-BVA-19 | Reserve: amount=20001 | Резерв и реальный остаток: отказ, баланс=20000 | integration | Запрет отрицательного баланса проверяется вместе с записью резерва в PostgreSQL |
| TC-CM-CREATE-01 | POST `/api/cards` со всеми валидными полями | 201. Создана одна ACTIVE-карта с Luhn-valid PAN и expiry=current+3 years. Outbox-событие обработано | integration | Создание карты, сохранение и обработка outbox требуют PostgreSQL/RabbitMQ |
| TC-CM-EQ-01 | GET по PAN с валидным Luhn | 200 и одна карта, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-03 | GET по PAN | 404, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-04 | PATCH status=BLOCKED | 200, изменено только поле status, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-05 | DELETE, затем GET и list | DELETE возвращает 204. Карта имеет DELETED и не выдается, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-06 | generate count=1 | 201, ровно одна валидная карта, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-07 | generate count=0 | 400, карт не создано, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-08 | reserve amount=1 | 200, баланс 19 999, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-09 | reserve amount=0 | 400, баланс неизменен, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-10 | reserve amount=20 001 | отказ, баланс неизменен, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-11 | reserve с 12 цифрами | 200, ровно один reserve, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-12 | повторить reserve с тем же RRN | 409, повторного списания нет, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-EQ-13 | reserve с буквами в RRN | 400, побочные эффекты по разделу 4 | integration | Сохранены HTTP-вызов и проверка побочных эффектов из раздела 4 практики 2, нужна реальная БД |
| TC-CM-LUHN-01 | Сгенерировать 100 карт и вычислить Luhn для каждого PAN | Все PAN состоят из 16 цифр, проходят Luhn и уникальны | integration | Генерация 100 карт и уникальность сохранённых PAN, один unit алгоритма Luhn не покрывает весь сценарий |

## Часть (г). Анализ пропорций

Единица подсчёта - уникальный ID тест-кейса, а не число запросов, assertions или строк входных данных. Старые: 18 Authorization BVA + 19 CMS BVA + 22 EQ/CREATE/LUHN + 25 Authorization pairwise + 26 CMS pairwise + 1 ручной критичный = **111**. Новые: 8 unit + 3 api + 11 integration + 2 e2e = **24**. Всего **135**.

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 1 | 8 | 9 | 6.67% |
| api / integration | 110 | 14 | 124 | 91.85% |
| e2e | 0 | 2 | 2 | 1.48% |
| **Итого** | **111** | **24** | **135** | **100%** |

Разделение среднего уровня для проверки арифметики:

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| api | 15 | 3 | 18 | 13.33% |
| integration | 95 | 11 | 106 | 78.52% |

Доли округлены до двух знаков. Это распределение проектируемого набора, а не число уже написанных автотестов или измерение покрытия кода.

### Почему получилось такое распределение

У набора широкая середина: 124 из 135 проверок относятся к API/integration. Причина не только в микросервисах. Исходная практика 2 связала большинство кейсов с проверками реальной БД: даже негативные pairwise-кейсы требуют доказать отсутствие карты, резерва или outbox. Сохранение этих результатов удерживает их на integration. В частности, расчёт лимитов реализован SQL, и mock LimitUsageRepository не проверит его корректность.

Это сильнее смещено к интеграциям, чем ориентиры лекции 5. Набор нельзя объявить хорошо сбалансированным только потому, что он микросервисный. **Дефицит дешёвых unit-проверок остаётся:** 9 из 135. Цена - более медленный прогон, сложнее подготовка данных и локализация падения. При дальнейшей автоматизации следует выделить из широких старых кейсов чистые проверки статуса, даты, баланса и валидации, а интеграционные сценарии оставить для SQL, транзакций, блокировок и очередей. Такое разделение изменит состав и число ID, здесь оно не выполнено задним числом ради 70/20/10.

В новых кейсах больше проверок нижнего уровня: 8 unit проверяют решения без сети, 3 api - HTTP-контракт отдельно от состояния БД, 11 integration - риски, которые mock скрывает. Два E2E оставлены для полной связности оплаты с журналом и для цепочки блокировки/разблокировки карты. Ещё один полный прогон на каждый неправильный BIN или граничную сумму не добавляет полезной информации относительно нижних уровней.

Перекоса ice-cream cone нет: E2E всего 2 из 135 (1.48%). Однако маленькая вершина не устраняет тяжёлую середину. Дефицита самих HTTP-проверок нет: 18 отдельных api-кейсов плюс ответы в integration. При этом контрактные проверки пока сосредоточены на CMS/Authorization/Gateway: покрытие всех endpoint и схем сообщений не доказано одним процентом. Для дальнейшего расширения полезны негативные HTTP-контракты Logger/Notification и схемы сообщений, а не искусственное увеличение E2E.

### Покрытие обязательных путей новыми кейсами

| Путь | ID | Почему этот уровень достаточен |
|---|---|---|
| Sync: валидация Gateway | U-01/U-02, A-03 | Метод и HTTP-обвязка проверяются отдельно |
| Sync: BIN-routing | U-03/U-04 | Достаточны PAN и таблица маршрутов |
| Sync: лимиты и баланс | U-05, I-01/I-02/I-11 | Инвариант объекта изолируется, SQL, блокировки и побочные эффекты требуют БД |
| Sync: CRUD | I-04, I-10, E-02, полный CRUD также в старых TC-CM-EQ-01/03/04/05 и CREATE-01 | Сохранение и outbox требуют настоящего хранилища, E2E нужен для связи статуса с оплатой |
| Sync: резервирование/откат | U-07/U-08, A-01, I-02/I-09 | Решение, контракт и реальное состояние проверяются на своих границах |
| Async: Logger | I-03, E-01/E-02 | Локальная доставка с повтором плюс связность полного контура |
| Async: publisher-confirm + reversal | I-06, U-07, I-09 | Проверяются ожидание брокера, запуск компенсации и восстановление резерва |
| Async: outbox → Notification | U-06, I-04/I-05/I-10 | Алгоритм retry, доставка, восстановление и атомарность |
| Async: DLQ и TTL | I-07/I-08 | Нужна реальная топология брокера |
| Eventual consistency | I-03/I-04/I-05, E-01/E-02 | Ожидание целевого состояния вместо фиксированного sleep |

В таблице покрытия префикс `P3-` опущен только для краткости. Диаграммы отражают наблюдаемый код, тесты I-06/I-07/I-10/I-11 формулируют требуемые гарантии, которые статический анализ не подтверждает. До исполнения они не являются доказанными runtime-дефектами.

[gw]: ../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java
[routing]: ../../services/switch/src/main/java/com/processing/service/RoutingService.java
[route]: ../../services/switch/src/main/java/com/processing/service/RouteService.java
[publisher]: ../../services/switch/src/main/java/com/processing/service/LoggerClient.java
[auth]: ../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java
[auth-controller]: ../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java
[rollback]: ../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java
[limits]: ../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java
[cms]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java
[card]: ../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java
[reservation]: ../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java
[outbox]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java
[scheduler]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java
[mq]: ../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java
[notification]: ../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java
[logger]: ../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java
