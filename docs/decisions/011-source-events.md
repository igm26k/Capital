# 011. Структурированные банковские наблюдения и сверка
Дата2026-10-16. Статус: foundation; финансовый apply реализуется отдельным пакетом.

Source event — частный вспомогательный ресурс автора, не новая сущность финансового sync mirror. Intake принимает только структурированные поля без полного уведомления/банковского пароля, проверяет текущую сессию, membership, generation и account/currency. Используются существующие auxiliary receipts с head lock; неизвестные результаты не переиздаются с новым ключом. Состояние intake review не меняет остатки/журнал.

Идентичность события: workspace + actor + connection UUID + hashed external notification identity. Record UUID является handle; другой handle с той же source identity и typed payload возвращает существующий event. Измененный payload дает source_event_conflict; сохраненное наблюдение не перезаписывается. Разные external identities сохраняются даже при одинаковых покупках. Доступ к самому workspace не открывает чужую частную банковскую metadata.

Reconcile сначала использует scoped external ID или явную ссылку на pending ID. Account/currency/kind conflict, удаленная исходная операция, status regression, неоднозначная identity или отмена без связи требуют review. Fuzzy amount/time/merchant не разрешает auto merge даже при единственном кандидате. Возвращаемая ID/version — только предложение: финансовый apply обязан отдельно проверить optimistic version/authorization и сохранить financial receipt атомарно. Пользовательские категории/теги/заметки не входят в банковский patch и не должны перезаписываться.

Реальные банки, провайдер, accounts/permissions и форматы остаются неизвестными. Синтетические unit/integration проверки подтверждают алгоритм и сохранение structured events, а не поддержку банка или работающую синхронизацию с ним.
