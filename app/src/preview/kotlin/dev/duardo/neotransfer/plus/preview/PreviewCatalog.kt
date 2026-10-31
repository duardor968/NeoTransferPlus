package dev.duardo.neotransfer.plus.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.app.routes.cardField
import dev.duardo.neotransfer.plus.designsystem.component.skeleton
import dev.duardo.neotransfer.plus.preview.PreviewData.bandec
import dev.duardo.neotransfer.plus.preview.PreviewData.banmet
import dev.duardo.neotransfer.plus.preview.PreviewData.bpa
import dev.duardo.neotransfer.plus.preview.PreviewData.cup
import dev.duardo.neotransfer.plus.preview.PreviewData.newCard
import dev.duardo.neotransfer.plus.preview.PreviewData.wallet
import dev.duardo.neotransfer.plus.ui.activity.ActivityScreen
import dev.duardo.neotransfer.plus.app.MarkedNotDoneReason
import dev.duardo.neotransfer.plus.app.NotDeliveredReason
import dev.duardo.neotransfer.plus.app.NotInHistoryReason
import dev.duardo.neotransfer.plus.app.PasswordInDoubt
import dev.duardo.neotransfer.plus.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.plus.ui.contacts.AllContactsScreen
import dev.duardo.neotransfer.plus.ui.contacts.CardEditScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.plus.ui.home.HomeScreen
import dev.duardo.neotransfer.plus.ui.lock.LockScreen
import dev.duardo.neotransfer.plus.ui.login.LoginScreen
import dev.duardo.neotransfer.plus.ui.model.ActivityContent
import dev.duardo.neotransfer.plus.ui.model.ActivityFilter
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.ActivityUi
import dev.duardo.neotransfer.plus.ui.model.AddCardUi
import dev.duardo.neotransfer.plus.ui.model.AllContactsUi
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.BillUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.CellUi
import dev.duardo.neotransfer.plus.ui.model.CardEditUi
import dev.duardo.neotransfer.plus.ui.model.CardSheetUi
import dev.duardo.neotransfer.plus.ui.model.ContactEditUi
import dev.duardo.neotransfer.plus.ui.model.ContactsContent
import dev.duardo.neotransfer.plus.ui.model.ContactsUi
import dev.duardo.neotransfer.plus.ui.model.DuplicateUi
import dev.duardo.neotransfer.plus.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.HomeUi
import dev.duardo.neotransfer.plus.ui.model.LockUi
import dev.duardo.neotransfer.plus.ui.model.LoginProblem
import dev.duardo.neotransfer.plus.ui.model.LoginUi
import dev.duardo.neotransfer.plus.ui.model.NotAddedKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.PasswordSettingsUi
import dev.duardo.neotransfer.plus.ui.model.PasswordSheetUi
import dev.duardo.neotransfer.plus.ui.model.PayQrUi
import dev.duardo.neotransfer.plus.ui.model.PayReviewUi
import dev.duardo.neotransfer.plus.ui.model.PaymentResultUi
import dev.duardo.neotransfer.plus.ui.model.ReceiveUi
import dev.duardo.neotransfer.plus.ui.model.RecipientPickUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.ReadProblem
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.SavedEditUi
import dev.duardo.neotransfer.plus.ui.model.ServiceFormUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.ServiceLookupUi
import dev.duardo.neotransfer.plus.ui.model.ServicesUi
import dev.duardo.neotransfer.plus.ui.model.SettingsUi
import dev.duardo.neotransfer.plus.ui.model.TransferAmountUi
import dev.duardo.neotransfer.plus.ui.model.TransferReviewUi
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import dev.duardo.neotransfer.plus.ui.pay.PayQrScreen
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheet
import dev.duardo.neotransfer.plus.ui.payment.PaymentResultScreen
import dev.duardo.neotransfer.plus.ui.receive.QrCode
import dev.duardo.neotransfer.plus.ui.receive.ReceiveScreen
import dev.duardo.neotransfer.plus.ui.services.ServiceFormScreen
import dev.duardo.neotransfer.plus.ui.services.ServicesScreen
import dev.duardo.neotransfer.plus.ui.settings.AboutScreen
import dev.duardo.neotransfer.plus.ui.settings.AddCardScreen
import dev.duardo.neotransfer.plus.ui.settings.LicensesScreen
import dev.duardo.neotransfer.plus.ui.settings.PasswordSettingsScreen
import dev.duardo.neotransfer.plus.ui.settings.SettingsScreen
import dev.duardo.neotransfer.plus.ui.shell.MainShell
import dev.duardo.neotransfer.plus.ui.shell.Tab
import dev.duardo.neotransfer.plus.ui.transfer.RecipientScreen
import dev.duardo.neotransfer.plus.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.plus.ui.transfer.TransferReviewScreen
import dev.duardo.neotransfer.plus.ui.model.Appearance as Theme

/** Every screen and state, by id. Ids are what `--es screen` takes and how captures are named. */
object PreviewCatalog {
    private val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        // Access ----------------------------------------------------------------------------------------------
        "lock" to { LockScreen(LockUi.Prompting, noop()) },
        "lock-cancelled" to { LockScreen(LockUi.Cancelled, noop()) },
        "login" to { LoginScreen(LoginUi.Start(), noop(), {}) },
        "login-offline" to { LoginScreen(LoginUi.Start(LoginProblem.Offline), noop(), {}) },
        "login-timeout" to { LoginScreen(LoginUi.Start(LoginProblem.Timeout), noop(), {}) },
        "login-web" to { LoginScreen(LoginUi.Web(loading = true), noop(), { FakeWebPage() }) },
        "login-connecting" to { LoginScreen(LoginUi.Connecting, noop(), {}) },

        // Home ------------------------------------------------------------------------------------------------
        "home" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, PreviewData.inProgress, PreviewData.recent), noop()) } },
        "home-bandec" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent), noop()) } },
        "home-banmet" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 2, emptyList(), PreviewData.recent), noop()) } },
        "home-refreshing" to {
            Tabbed(Tab.Home) {
                HomeScreen(HomeUi(listOf(wallet[0].copy(refreshing = true), wallet[1], wallet[2]), 0, emptyList(), PreviewData.recent, RefreshState.Refreshing), noop())
            }
        },
        // The balance line after a failed read, one per kind of failure.
        "home-offline" to { HomeFailed(ReadProblem.Offline) },
        "home-timeout" to { HomeFailed(ReadProblem.Timeout) },
        "home-rejected" to { HomeFailed(ReadProblem.Rejected) },
        "home-unrecognized" to { HomeFailed(ReadProblem.Unrecognized) },
        "home-expired" to { HomeFailed(ReadProblem.Expired) },
        "home-local" to { HomeFailed(ReadProblem.Local) },
        "home-loading" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(emptyList(), 0, emptyList(), null, RefreshState.Refreshing), noop()) } },
        "home-no-cards" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(emptyList(), 0, emptyList(), emptyList()), noop()) } },
        "home-unknown-balance" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(WalletCardUi(bpa, BalanceUi.Unknown)), 0, emptyList(), emptyList()), noop()) }
        },
        "home-card-complete" to {
            Tabbed(Tab.Home) {
                HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent), noop(), sheet = CardSheetUi(bandec, "92051299876"), sheetActions = noop())
            }
        },
        "home-card" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, emptyList(), PreviewData.recent), noop(), sheet = CardSheetUi(bpa, ""), sheetActions = noop()) }
        },
        "home-recharge" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, emptyList(), PreviewData.recent, recharge = PreviewData.rechargeTargets), noop()) }
        },
        "home-recharge-new" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, emptyList(), PreviewData.recent, recharge = emptyList()), noop()) } },

        // Activity --------------------------------------------------------------------------------------------
        "activity" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Items(PreviewData.history)), noop()) } },
        "activity-cards" to {
            Tabbed(Tab.Activity) {
                ActivityScreen(activity(ActivityContent.Items(PreviewData.cardMovements)).copy(source = ActivitySource.Cards, card = bpa.id), noop())
            }
        },
        "activity-search" to {
            Tabbed(Tab.Activity) {
                ActivityScreen(activity(ActivityContent.Items(listOf(PreviewData.confirmedToJuan))).copy(query = "juan", filter = ActivityFilter.Out), noop())
            }
        },
        "activity-loading" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Loading), noop()) } },
        "activity-empty" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Empty), noop()) } },
        "operation-confirmed" to { OperationDetailScreen(PreviewData.confirmedToJuan, noop()) },
        "operation-incoming" to { OperationDetailScreen(PreviewData.incomingFromDaniela, noop()) },
        "operation-qr" to { OperationDetailScreen(PreviewData.qrCafe, noop()) },
        "operation-unconfirmed" to { OperationDetailScreen(PreviewData.unconfirmedToYunior, noop()) },
        "operation-unconfirmed-missing" to { OperationDetailScreen(missing, noop()) },
        "operation-not-done-confirm" to { OperationDetailScreen(missing, noop(), confirmingNotDone = true) },
        "operation-not-done" to {
            OperationDetailScreen(missing.copy(status = OperationStatus.NotDone, reason = MarkedNotDoneReason, canMarkNotDone = false), noop())
        },
        "operation-rejected" to { OperationDetailScreen(PreviewData.landlineRejected, noop()) },
        "operation-other" to { OperationDetailScreen(PreviewData.nauta, noop()) },
        "operation-card-movement" to { OperationDetailScreen(PreviewData.cardMovements[2], noop()) },

        // Contacts --------------------------------------------------------------------------------------------
        "contacts" to {
            Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.People(PreviewData.people), PreviewData.everyone.size, addressBook = true), noop()) }
        },
        "contacts-empty" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.People(emptyList()), 214, addressBook = true), noop()) } },
        "contacts-no-access" to {
            Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.People(listOf(PreviewData.carmen)), 1, addressBook = false), noop()) }
        },
        "contacts-search" to {
            Tabbed(Tab.Contacts) {
                val found = PreviewData.everyone.filter { "car" in it.name.lowercase() }
                val people = PreviewData.people.filter { it in found }
                ContactsScreen(ContactsUi("car", ContactsContent.Results(people, found - people.toSet()), PreviewData.everyone.size, addressBook = true), noop())
            }
        },
        "contacts-all" to { AllContactsScreen(AllContactsUi("", PreviewData.everyone), noop()) },
        "contact" to { ContactDetailScreen(PreviewData.juan, noop(), movements = PreviewData.juanMovements) },
        "contact-no-card" to { ContactDetailScreen(PreviewData.rafael, noop()) },
        "contact-app-only" to { ContactDetailScreen(PreviewData.carmen, noop()) },
        "contact-delete" to { ContactDetailScreen(PreviewData.carmen, noop(), confirmingDelete = true) },
        "contact-new" to {
            ContactEditScreen(
                ContactEditUi(null, "Abuela Carmen", cardField("9205950003124476", "Casa"), listOf(EditablePhoneUi("p1", "54417720")), canSave = true),
                noop(),
            )
        },
        "contact-edit" to {
            ContactEditScreen(
                ContactEditUi(
                    PreviewData.carmen.id, "Abuela Carmen", null,
                    listOf(EditablePhoneUi("p1", "53124578"), EditablePhoneUi("p2", "6441772", error = "Número incompleto")),
                    canSave = false,
                ),
                noop(),
            )
        },
        "card-new" to { CardEditScreen(CardEditUi(PreviewData.rafael.name, false, cardField("", ""), canSave = false), noop()) },
        "card-new-detected" to { CardEditScreen(CardEditUi(PreviewData.rafael.name, false, cardField("92250611", ""), canSave = false), noop()) },
        "card-new-metropolitano" to {
            CardEditScreen(CardEditUi(PreviewData.rafael.name, false, cardField("9205950003124476", "Ahorros"), canSave = true), noop())
        },
        "card-new-error" to { CardEditScreen(CardEditUi(PreviewData.rafael.name, false, cardField("9204067704229017", ""), canSave = false), noop()) },
        "card-new-taken" to {
            CardEditScreen(CardEditUi(PreviewData.juan.name, false, cardField("9204069901447185", "", taken = listOf("9204069901447185")), canSave = false), noop())
        },
        "card-new-unknown" to { CardEditScreen(CardEditUi(PreviewData.rafael.name, false, cardField("9204778800", ""), canSave = false), noop()) },
        "card-edit" to { CardEditScreen(CardEditUi(PreviewData.juan.name, true, cardField("9204123331880422", "Ahorros"), canSave = true), noop()) },

        // Services --------------------------------------------------------------------------------------------
        "services" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(PreviewData.savedReferences), noop()) } },
        "services-new" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(emptyList()), noop()) } },
        "service-electricity" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Electricity, "01234567891",
                    references = PreviewData.savedReferences.filter { it.kind == ServiceKind.Electricity }, selectedReference = "01234567891",
                    lookup = ServiceLookupUi.Bills(
                        "Laura Méndez Soto",
                        listOf(
                            BillUi("b-10", "Octubre 2026", cup("412.35"), paid = false),
                            BillUi("b-09", "Septiembre 2026", cup("387.10"), paid = true),
                        ),
                        selected = "b-10",
                    ),
                    currency = "CUP", cards = wallet, source = 0, canPay = true, saved = true,
                ),
                noop(),
            )
        },
        "service-saved" to { ServiceFormScreen(savedElectricity(), noop()) },
        "service-saved-edit" to { ServiceFormScreen(savedElectricity().copy(edit = SavedEditUi("Casa")), noop()) },
        "service-saved-remove" to { ServiceFormScreen(savedElectricity().copy(edit = SavedEditUi("Casa", removing = true)), noop()) },
        "service-electricity-new" to {
            ServiceFormScreen(ServiceFormUi(ServiceKind.Electricity, "", lookup = ServiceLookupUi.None, currency = "CUP", cards = wallet, source = 0, canPay = false), noop())
        },
        "service-loading" to {
            ServiceFormScreen(ServiceFormUi(ServiceKind.Landline, "78302214", lookup = ServiceLookupUi.Loading, currency = "CUP", cards = wallet, source = 0, canPay = false), noop())
        },
        "service-landline" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Landline, "78302214", lookup = ServiceLookupUi.PhoneBill("Laura Méndez Soto", cup("155.40")),
                    currency = "CUP", cards = wallet, source = 1, canPay = true,
                ),
                noop(),
            )
        },
        "service-nauta" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Nauta, "laura.mendez",
                    lookup = ServiceLookupUi.TopUp("Laura Méndez Soto", debt = null, min = cup("25.00"), max = cup("2500.00")),
                    amount = "300", currency = "CUP", cards = wallet, source = 0, canPay = true,
                ),
                noop(),
            )
        },
        "service-nauta-home" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.NautaHome, "laura.mendez.hogar",
                    lookup = ServiceLookupUi.TopUp("Laura Méndez Soto", debt = cup("320.00"), min = cup("100.00"), max = null),
                    amount = "", currency = "CUP", cards = wallet, source = 0, canPay = false,
                ),
                noop(),
            )
        },
        "service-failed" to {
            ServiceFormScreen(
                ServiceFormUi(ServiceKind.Electricity, "01234567899", lookup = ServiceLookupUi.Failed("ENZONA no encontró ese código de cliente."), currency = "CUP", cards = wallet, source = 0, canPay = false),
                noop(),
            )
        },

        // Transfer --------------------------------------------------------------------------------------------
        "transfer-recipient" to {
            RecipientScreen(
                RecipientPickUi(
                    "", null, null,
                    recent = listOf(
                        PreviewData.juanRecipient,
                        RecipientUi.Card(PreviewData.maria.cards.first(), PreviewData.maria.name, PreviewData.maria.id),
                        RecipientUi.Card(ForeignCardUi("x", "9204125551880420", "BPA", currency = "CUP")),
                        RecipientUi.Card(PreviewData.daniela.cards.first(), PreviewData.daniela.name, PreviewData.daniela.id),
                    ),
                    contacts = PreviewData.contacts.filter { it.cards.isNotEmpty() },
                ),
                noop(),
            )
        },
        "transfer-recipient-empty" to { RecipientScreen(RecipientPickUi("", null, null, emptyList(), emptyList()), noop()) },
        "transfer-recipient-typed" to {
            RecipientScreen(RecipientPickUi("9204 0688 0012 3459", ForeignCardUi("t", "9204068800123459", "BANDEC", currency = "CUP"), null, emptyList(), emptyList()), noop())
        },
        "transfer-recipient-search" to {
            // As the search finds them: the name contains what is typed; people with a card first.
            val found = PreviewData.everyone.filter { "car" in it.name.lowercase() }.sortedBy { it.cards.isEmpty() }
            RecipientScreen(RecipientPickUi("car", null, null, emptyList(), found), noop())
        },
        "transfer-recipient-cards" to {
            RecipientScreen(RecipientPickUi("", null, null, emptyList(), PreviewData.contacts.filter { it.cards.isNotEmpty() }, chooseCardOf = PreviewData.juan), noop())
        },
        "transfer-amount" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "300", "CUP", wallet, 0, false, true), noop()) },
        "transfer-amount-over" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "4500.5", "CUP", wallet, 1, true, true), noop()) },
        "transfer-amount-account" to {
            TransferAmountScreen(TransferAmountUi(PreviewData.accountRecipient, "750", "CUP", wallet, 2, false, true, fixed = true), noop())
        },
        "transfer-review" to { TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa), noop()) },
        "transfer-review-new" to {
            TransferReviewScreen(TransferReviewUi(RecipientUi.Card(ForeignCardUi("t", "9204068800123459", "BANDEC", currency = "CUP")), cup("1250.00"), bandec), noop())
        },
        "transfer-review-account" to { TransferReviewScreen(TransferReviewUi(PreviewData.accountRecipient, cup("750.00"), banmet), noop()) },
        "transfer-duplicate" to {
            TransferReviewScreen(
                TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa, duplicate = DuplicateUi(cup("300.00"), PreviewData.juan.name, PreviewData.ago(3))),
                noop(),
            )
        },
        "transfer-password" to {
            TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa), noop())
            PasswordSheet(PasswordSheetUi("482915", "Guardar y transferir"), noop())
        },
        "transfer-password-again" to {
            TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa), noop())
            PasswordSheet(PasswordSheetUi("", "Guardar y transferir", notice = PasswordInDoubt), noop())
        },
        "payment-sending" to { PaymentResultScreen(PaymentResultUi(PreviewData.sendingToMaria), noop()) },
        "payment-confirmed" to {
            PaymentResultScreen(PaymentResultUi(PreviewData.sendingToMaria.copy(status = OperationStatus.Confirmed, transactionId = "4f1c9a20-7b3e-4d61-8e0a-52c7d9b1a6e3")), noop())
        },
        "payment-confirmed-new" to {
            PaymentResultScreen(
                PaymentResultUi(
                    PreviewData.sendingToMaria.copy(title = "9204 0688 0012 3459", contactId = null, status = OperationStatus.Confirmed),
                    canSaveContact = true,
                ),
                noop(),
            )
        },
        "payment-not-sent" to {
            PaymentResultScreen(
                PaymentResultUi(PreviewData.sendingToMaria.copy(status = OperationStatus.NotSent, reason = NotDeliveredReason)),
                noop(),
            )
        },
        "payment-unconfirmed" to { PaymentResultScreen(PaymentResultUi(PreviewData.unconfirmedToYunior), noop()) },
        "payment-unconfirmed-answered" to {
            PaymentResultScreen(
                PaymentResultUi(PreviewData.unconfirmedToYunior.copy(
                    reason = "ENZONA respondió: “Contraseña de pago incorrecta”. Revisa la actividad antes de repetirla.",
                )),
                noop(),
            )
        },
        "payment-rejected" to { PaymentResultScreen(PaymentResultUi(PreviewData.landlineRejected), noop()) },

        // Pay and receive -------------------------------------------------------------------------------------
        "pay-scanning" to { PayQrScreen(PayQrUi.Scanning, noop(), { FakeCamera() }) },
        "pay-resolving" to { PayQrScreen(PayQrUi.Resolving, noop(), { FakeCamera(locked = true) }) },
        "pay-review" to {
            PayQrScreen(
                PayQrUi.Review(PayReviewUi("Cafetería La Esquina", "480.00", false, "", "CUP", wallet, 0, canPay = true)),
                noop(), { FakeCamera(locked = true) },
            )
        },
        "pay-review-open-amount" to {
            PayQrScreen(
                PayQrUi.Review(PayReviewUi("Mercado La Plaza", "", true, "Viandas", "CUP", wallet, 1, canPay = false)),
                noop(), { FakeCamera(locked = true) },
            )
        },
        "pay-unsupported" to { PayQrScreen(PayQrUi.Unsupported("Este QR no es de pago"), noop(), { FakeCamera(locked = true) }) },
        "pay-unsupported-card" to {
            PayQrScreen(PayQrUi.Unsupported("Para pagar este código hace falta una tarjeta en USD", addCard = true), noop(), { FakeCamera(locked = true) })
        },
        "receive" to { ReceiveScreen(receive(), noop()) },
        "receive-amount" to { ReceiveScreen(receive().copy(amount = cup("1500.00")), noop()) },
        "receive-ask" to { ReceiveScreen(receive().copy(asking = "1500"), noop()) },
        "receive-loading" to { ReceiveScreen(receive().copy(payload = null), noop()) },
        "receive-failed" to { ReceiveScreen(receive().copy(payload = null, error = "Sin conexión."), noop()) },

        // Settings --------------------------------------------------------------------------------------------
        "settings" to { SettingsScreen(settings(), noop()) },
        "settings-card-menu" to { SettingsScreen(settings().copy(cardMenu = bandec.id), noop()) },
        "settings-card-busy" to {
            SettingsScreen(settings().copy(cards = PreviewData.accountCards.map { if (it.card == bandec) it.copy(busy = true) else it }), noop())
        },
        "settings-card-remove" to { SettingsScreen(settings().copy(removing = bandec), noop()) },
        "card-add" to { AddCardScreen(AddCardUi.Form(CardUi("new", "", CardArt.Neutral, null, null, "", "Laura Méndez Soto"), "", ""), noop()) },
        "card-add-typed" to { AddCardScreen(AddCardUi.Form(newCard, newCard.number!!, "0830", canContinue = true), noop()) },
        "card-add-checking" to { AddCardScreen(AddCardUi.Form(newCard, newCard.number!!, "0830", canContinue = true, checking = true), noop()) },
        "card-add-unlisted" to {
            AddCardScreen(AddCardUi.Form(newCard, newCard.number!!, "0830", numberError = "ENZONA no admite tarjetas nuevas de\u00A0BPA"), noop())
        },
        "card-add-cells" to { AddCardScreen(AddCardUi.Cells(newCard, cells("", "", "", ""), canSend = false), noop()) },
        "card-add-cells-typed" to { AddCardScreen(AddCardUi.Cells(newCard, cells("37", "18", "5", "9"), canSend = true), noop()) },
        "card-add-sending" to { AddCardScreen(AddCardUi.Sending(newCard), noop()) },
        "card-add-failed" to { AddCardScreen(AddCardUi.NotAdded(NotAddedKind.Refused, "Coordenadas incorrectas."), noop()) },
        "card-add-unconfirmed" to {
            AddCardScreen(
                AddCardUi.NotAdded(NotAddedKind.Unconfirmed, "ENZONA tardó demasiado en responder. Mira en Ajustes si la tarjeta aparece antes de intentarlo de nuevo."),
                noop(),
            )
        },
        "card-add-done" to { AddCardScreen(AddCardUi.Added(newCard), noop()) },
        "settings-background" to {
            SettingsScreen(SettingsUi(PreviewData.account, passwordSaved = true, Theme.System, notifications = true, version = "0.1.0", batteryExempt = false, autostart = true), noop())
        },
        "settings-autostart" to {
            SettingsScreen(
                SettingsUi(PreviewData.account, passwordSaved = true, Theme.System, notifications = true, version = "0.1.0", autostart = true, offeringAutostart = true),
                noop(),
            )
        },
        "settings-sign-out" to {
            SettingsScreen(SettingsUi(PreviewData.account, passwordSaved = false, Theme.System, notifications = false, version = "0.1.0", confirmingSignOut = true), noop())
        },
        "settings-password" to { PasswordSettingsScreen(PasswordSettingsUi(saved = false, value = ""), noop()) },
        "settings-password-saved" to { PasswordSettingsScreen(PasswordSettingsUi(saved = true, value = "482915"), noop()) },
        "settings-password-doubt" to { SettingsScreen(settings().copy(passwordUnverified = true), noop()) },
        "settings-password-remove" to { PasswordSettingsScreen(PasswordSettingsUi(saved = true, value = "", confirmingRemoval = true), noop()) },
        "settings-about" to { AboutScreen("0.1.0", noop()) },
        "settings-licenses" to {
            LicensesScreen(
                listOf(
                    "Inter" to "Copyright 2020 The Inter Project Authors.\n\nSIL Open Font License, Version 1.1.",
                    "Arimo" to "Copyright 2010 Google Corporation.\n\nSIL Open Font License, Version 1.1.",
                    "Material Symbols" to "Copyright Google LLC.\n\nApache License, Version 2.0.",
                ),
                {},
            )
        },
    )

    val ids: List<String> get() = screens.keys.toList()

    @Composable
    fun content(id: String) {
        screens.getValue(id)()
    }
}

/** The bill of a saved reference, answered: the bar offers to edit it. */
private fun savedElectricity() = ServiceFormUi(
    ServiceKind.Electricity, "01234567891",
    references = PreviewData.savedReferences.filter { it.kind == ServiceKind.Electricity }, selectedReference = "01234567891",
    lookup = ServiceLookupUi.Bills("Laura Méndez Soto", listOf(BillUi("b-10", "Octubre 2026", cup("412.35"), paid = false)), selected = "b-10"),
    currency = "CUP", cards = wallet, source = 0, canPay = true, saved = true,
)

private fun receive() = ReceiveUi(PreviewData.ownQrPayload, null, "CUP", PreviewData.account.name, PreviewData.account.username)

/** A transfer still unconfirmed a day later, missing from the history read since. */
private val missing = PreviewData.unconfirmedToYunior.copy(at = PreviewData.at(2, 10, 5), reason = NotInHistoryReason, canMarkNotDone = true)

private fun settings() =
    SettingsUi(PreviewData.account, passwordSaved = true, Theme.System, notifications = true, version = "0.1.0", cards = PreviewData.accountCards)

/** The cells as ENZONA names them on its own form. */
private fun cells(vararg typed: String) =
    listOf("Coordenada A02", "Coordenada D05", "Posición 2 del PIN", "Posición 4 del PIN").zip(typed.toList(), ::CellUi)

private fun activity(content: ActivityContent) =
    ActivityUi(ActivitySource.Account, "", ActivityFilter.All, listOf(bpa, bandec, banmet), null, content, olderFrom = java.time.LocalDate.of(2026, 10, 7))

@Composable
private fun Tabbed(tab: Tab, content: @Composable () -> Unit) {
    MainShell(tab, {}, content = content)
}

@Composable
private fun HomeFailed(problem: ReadProblem) {
    Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, emptyList(), PreviewData.recent, RefreshState.Failed(problem, PreviewData.ago(0))), noop()) }
}

/** Stand-in for ENZONA's sign-in page: its layout without its brand. */
@Composable
private fun FakeWebPage() {
    Column(Modifier.fillMaxSize().background(Color.White).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(120.dp, 22.dp).skeleton())
        Box(Modifier.fillMaxWidth().height(52.dp).skeleton(RoundedCornerShape(8.dp)))
        Box(Modifier.fillMaxWidth().height(52.dp).skeleton(RoundedCornerShape(8.dp)))
        Box(Modifier.fillMaxWidth().height(48.dp).skeleton(RoundedCornerShape(24.dp)))
    }
}

/**
 * Stand-in for the scanner: a dim camera feed with a merchant QR, the scanner's brackets (landed when [locked]) and
 * its bottom controls. Pagar draws its own close button over it.
 */
@Composable
private fun FakeCamera(locked: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3530), Color(0xFF1C1A18), Color(0xFF0E0D0C))))) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(236.dp)
                .graphicsLayer { rotationZ = -3f }
                .background(Color(0xFFF2F0EC), RoundedCornerShape(12.dp))
                .padding(22.dp),
        ) { QrCode("07CLE00417PAGO", Modifier.fillMaxSize(), Color(0xFF1A1A1A)) }
        val frame = MaterialTheme.colorScheme.primary.takeIf { locked } ?: Color.White
        Box(
            Modifier.align(Alignment.Center).size(if (locked) 214.dp else 260.dp).drawBehind {
                val stroke = 4.dp.toPx()
                val arm = 34.dp.toPx()
                val r = 18.dp.toPx()
                val w = size.width
                val h = size.height
                listOf(Triple(Offset(0f, 0f), 1f, 1f), Triple(Offset(w, 0f), -1f, 1f), Triple(Offset(0f, h), 1f, -1f), Triple(Offset(w, h), -1f, -1f))
                    .forEach { (o, dx, dy) ->
                        val path = Path().apply {
                            moveTo(o.x, o.y + dy * arm)
                            lineTo(o.x, o.y + dy * r)
                            quadraticTo(o.x, o.y, o.x + dx * r, o.y)
                            lineTo(o.x + dx * arm, o.y)
                        }
                        drawPath(path, frame, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
            },
        )
        if (!locked) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraButton(Ds.drawable.ic_photo_library)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("1×", "2×").forEachIndexed { index, label ->
                        Box(
                            Modifier.size(40.dp).background(if (index == 0) Color.White.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            CameraButton(Ds.drawable.ic_flashlight_off)
        }
    }
}

@Composable
private fun CameraButton(icon: Int) {
    Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color.White)
    }
}
