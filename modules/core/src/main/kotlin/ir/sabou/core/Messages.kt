package ir.sabou.core

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException

/**
 * Turns any failure into one clear Persian sentence for the user. Specific states get specific
 * guidance; everything else falls back to the error's own message. Unknown technical failures
 * never show a stack trace or an English code.
 */
object Messages {
    private val states = mapOf(
        "ATTENDANCE:PERIOD_APPROVED" to "حقوق این دوره تأیید شده است؛ حضور و غیاب آن قابل تغییر نیست.",
        "COLLECTION:ALREADY_REVERSED" to "این دریافت قبلاً برگشت خورده است.",
        "CUSTOMER:CREDIT_LIMIT_EXCEEDED" to "مبلغ نسیه از سقف اعتبار مشتری بیشتر است.",
        "CUSTOMER:DUPLICATE" to "مشتری با این نام قبلاً ثبت شده است.",
        "CUSTOMER:INACTIVE" to "این مشتری غیرفعال است.",
        "DAILY_SALE:ALREADY_POSTED" to "فروش این روز قبلاً ثبت نهایی شده است.",
        "DAILY_SALE:HAS_COLLECTIONS" to "برای نسیه‌های این روز دریافت ثبت شده؛ ابتدا آن دریافت‌ها را برگشت بزنید.",
        "DAILY_SALE:NOT_POSTED" to "فروش این روز هنوز ثبت نهایی نشده است.",
        "DAILY_SALE:SETTLEMENT_MISMATCH" to "جمع روش‌های تسویه با مبلغ قابل تسویه برابر نیست.",
        "EMPLOYEE:DUPLICATE_NATIONAL_ID" to "کارمندی با این کد ملی قبلاً ثبت شده است.",
        "EMPLOYEE:ALREADY_ENDED" to "همکاری این کارمند قبلاً پایان یافته است.",
        "EMPLOYEE:PERIOD_APPROVED" to "حقوق کامل ماهی که بعد از این تاریخ تمام می‌شود تأیید شده است؛ تاریخ پایان را آخرین روز آن ماه یا بعد از آن بگذارید.",
        "EMPLOYEE:START_IN_APPROVED_PERIOD" to "حقوق ماهی که این تاریخ شروع در آن است تأیید شده است؛ تاریخ شروع را بعد از آخرین ماه تأییدشده بگذارید.",
        "PAYROLL_RUN:STALE" to "از زمان محاسبه، اطلاعات کارکنان یا حضور و غیاب تغییر کرده است. لیست حقوق را دوباره محاسبه کنید.",
        "EMPLOYEE:NOT_EMPLOYED_ON_DATE" to "این کارمند در این روز مشغول به کار نبوده است.",
        "ITEM:DUPLICATE_NAME" to "کالایی با این نام وجود دارد.",
        "ITEM:INACTIVE" to "این کالا غیرفعال است.",
        "JOURNAL:ALREADY_REVERSED" to "این سند قبلاً برگشت خورده است.",
        "JOURNAL:IS_REVERSAL" to "سند برگشتی را نمی‌توان دوباره برگشت زد.",
        "LOCATION:DUPLICATE_NAME" to "انباری با این نام در این شعبه وجود دارد.",
        "LOCATION:INACTIVE" to "این انبار غیرفعال است.",
        "PAYROLL_LIABILITY:AMOUNT_EXCEEDS_OUTSTANDING" to "مبلغ از بدهی باقی‌مانده بیشتر است.",
        "PAYROLL_POLICY:DUPLICATE_VERSION" to "این نسخه از پارامترهای حقوق قبلاً ثبت شده است.",
        "PAYROLL_POLICY:NOT_CONFIGURED" to "پارامترهای قانونی حقوق برای این دوره تعریف نشده است. مالک باید آن‌ها را در تنظیمات وارد کند.",
        "PAYROLL_POLICY:OVERLAPPING_PERIOD" to "بازه این پارامترها با نسخه دیگری هم‌پوشانی دارد.",
        "PAYROLL_RUN:HAS_PAYMENTS" to "برای این لیست حقوق پرداخت ثبت شده است؛ ابتدا پرداخت‌ها را برگشت بزنید.",
        "PAYROLL_RUN:LIABILITY_ALREADY_REMITTED" to "بیمه یا مالیات این دوره پرداخت شده است.",
        "PAYROLL_RUN:NO_EMPLOYEES" to "در این ماه کارمندی در این شعبه مشغول به کار نبوده است.",
        "PAYROLL_RUN:PERIOD_OVERLAP" to "برای این بازه قبلاً حقوق محاسبه شده است.",
        "PAYROLL_RUN:SAME_PERSON_CALCULATED" to "تأییدکننده حقوق باید فردی غیر از محاسبه‌کننده باشد.",
        "PAYSLIP:AMOUNT_EXCEEDS_UNPAID" to "مبلغ از خالص پرداختنی باقی‌مانده بیشتر است.",
        "PERIOD:NOT_CLOSED" to "این دوره بسته نیست.",
        "PERIOD:OVERLAP" to "این بازه با دوره بسته‌شده دیگری هم‌پوشانی دارد.",
        "PURCHASE_INVOICE:DUPLICATE_NUMBER" to "این شماره فاکتور برای این تأمین‌کننده قبلاً ثبت شده است.",
        "PURCHASE_INVOICE:HAS_ACTIVE_PAYMENTS" to "این فاکتور پرداخت دارد؛ ابتدا پرداخت‌ها را برگشت بزنید.",
        "PURCHASE_INVOICE:HAS_RETURNS" to "برای این فاکتور مرجوعی ثبت شده است.",
        "PURCHASE_INVOICE:PAYMENT_EXCEEDS_OUTSTANDING" to "مبلغ پرداخت از مانده فاکتور بیشتر است.",
        "PURCHASE_INVOICE:HAS_CREDITS" to "اعتبار مرجوعی روی این فاکتور اعمال شده است؛ ابتدا آن را آزاد کنید.",
        "PURCHASE_INVOICE:HAS_RESOLVED_LINES" to "ردیف‌های در انتظار بررسی این فاکتور تعیین تکلیف شده‌اند؛ برگشت کل فاکتور ممکن نیست. مرجوعی یا سند اصلاحی ثبت کنید.",
        "SUPPLIER_CREDIT:EXCEEDS_AVAILABLE" to "مبلغ از اعتبار استفاده‌نشده‌ی این تأمین‌کننده بیشتر است.",
        "SUPPLIER_REFUND:ALREADY_REVERSED" to "این استرداد قبلاً برگشت خورده است.",
        "CREDIT_ALLOCATION:ALREADY_RELEASED" to "این اعتبار قبلاً آزاد شده است.",
        "REVIEW_LINE:ALREADY_RESOLVED" to "این ردیف قبلاً تعیین تکلیف شده است.",
        "PURCHASE_ORDER:RECEIVED" to "این سفارش تحویل گرفته شده است.",
        "PURCHASE_ORDER:CANCELLED" to "این سفارش لغو شده است.",
        "PURCHASE_ORDER:OPEN" to "این سفارش هنوز باز است.",
        "PURCHASE_INVOICE:NOT_APPROVED" to "این فاکتور هنوز تأیید نشده است؛ پرداخت پس از تأیید ممکن است.",
        "PURCHASE_INVOICE:ALREADY_APPROVED" to "این فاکتور تأییدهای لازم را گرفته است.",
        "PURCHASE_INVOICE:SAME_APPROVER" to "هر مرحله‌ی تأیید باید با فرد دیگری باشد.",
        "PURCHASE_INVOICE:RECORDER_CANNOT_APPROVE" to "کسی که فاکتور را ثبت کرده نمی‌تواند آن را تأیید کند.",
        "CHEQUE:MOVED_ON" to "وضعیت این چک بعد از این سند تغییر کرده است (مثلاً واگذار به بانک، وصول یا پاس شده). ابتدا آن مرحله را برگردانید.",
        "CHEQUE:IN_HAND" to "این چک در صندوق است و این عملیات برایش ممکن نیست.",
        "CHEQUE:DEPOSITED" to "این چک به بانک واگذار شده است؛ ابتدا آن را پس بگیرید یا وصول کنید.",
        "CHEQUE:COLLECTED" to "این چک وصول شده است.",
        "CHEQUE:ENDORSED" to "این چک به دیگری واگذار شده است.",
        "CHEQUE:BOUNCED" to "این چک برگشت خورده است.",
        "CHEQUE:SETTLED" to "این چک برگشتی قبلاً تسویه شده است.",
        "CHEQUE:ISSUED" to "این چک هنوز پاس نشده است.",
        "CHEQUE:CLEARED" to "این چک پاس شده است.",
        "CHEQUE:VOID" to "این چک باطل شده است.",
        "DEPRECIATION:NOTHING_TO_BOOK" to "تا این تاریخ استهلاکی برای ثبت نیست.",
        "DEPRECIATION_RUN:NOT_LATEST" to "فقط آخرین استهلاک ثبت‌شده را می‌توان برگرداند.",
        "DEPRECIATION_RUN:REVERSED" to "این استهلاک قبلاً برگشت خورده است.",
        "FIXED_ASSET:DISPOSED" to "این دارایی واگذار شده است.",
        "STOCK_COUNT:PENDING_EXISTS" to "برای این انبار یک انبارگردانی در انتظار تأیید هست؛ اول آن تأیید یا رد شود.",
        "STOCK_COUNT:SAME_PERSON" to "کسی که شمارش کرده نمی‌تواند اختلاف‌های آن را تأیید کند.",
        "STOCK_COUNT:POSTED" to "این انبارگردانی قبلاً تأیید و ثبت شده است.",
        "STOCK_COUNT:REJECTED" to "این انبارگردانی رد شده است.",
        "ITEM:SUPPLIER_NOT_APPROVED" to "این تأمین‌کننده برای یکی از کالاهای سفارش در فهرست تأمین‌کنندگان مجاز نیست. فهرست مجاز را در تعریف کالا ببینید.",
        "RECEIVABLE:AMOUNT_EXCEEDS_OUTSTANDING" to "مبلغ از مانده طلب بیشتر است.",
        "RECIPE:NOT_AFTER_LATEST_VERSION" to "تاریخ شروع رسپی جدید باید بعد از آخرین نسخه باشد.",
        "RECIPE:NO_VERSION_ON_DATE" to "برای یکی از اقلام منو در این تاریخ رسپی تعریف نشده است.",
        "SALARY_PAYMENT:ALREADY_REVERSED" to "این پرداخت قبلاً برگشت خورده است.",
        "SALES_DAY:CLOSED" to "این روز فروش بسته شده است.",
        "SALES_DAY:NOT_CLOSED" to "این روز فروش بسته نیست.",
        "STOCK_MOVEMENT:ALREADY_REVERSED" to "این گردش انبار قبلاً برگشت خورده است.",
        "SUPPLIER:DUPLICATE_NAME" to "تأمین‌کننده‌ای با این نام وجود دارد.",
        "SUPPLIER:INACTIVE" to "این تأمین‌کننده غیرفعال است.",
        "SUPPLIER_PAYMENT:ALREADY_REVERSED" to "این پرداخت قبلاً برگشت خورده است.",
        "TREASURY_ACCOUNT:DUPLICATE_NAME" to "حسابی با این نام در این محدوده وجود دارد.",
        "TREASURY_ACCOUNT:INACTIVE" to "این حساب غیرفعال است.",
        "TREASURY_MOVEMENT:ALREADY_REVERSED" to "این گردش قبلاً برگشت خورده است.",
        "USER:DUPLICATE_USERNAME" to "این نام کاربری قبلاً استفاده شده است.",
        "USER:LAST_OWNER" to "آخرین مالک را نمی‌توان غیرفعال کرد.",
        "USER:LOCKED" to "به دلیل تلاش‌های ناموفق، ورود موقتاً قفل شده است. کمی بعد دوباره تلاش کنید.",
        "USER:LOCKED_UNTIL_RESET" to "به دلیل تلاش‌های ناموفق زیاد، این حساب قفل شده است. مالک باید رمز را بازنشانی کند.",
        "USERS:ALREADY_BOOTSTRAPPED" to "مالک قبلاً تعریف شده است.",
        "PAYROLL_RUN:APPROVED" to "این لیست حقوق تأیید شده است.",
        "PAYROLL_RUN:REVERSED" to "این لیست حقوق برگشت خورده است.",
        "PAYROLL_RUN:DRAFT" to "این لیست حقوق هنوز تأیید نشده است.",
        "DAILY_SALE:REVERSED" to "فروش این روز برگشت خورده است.",
        "DAILY_SALE:POSTED" to "فروش این روز ثبت نهایی شده است.",
        "PURCHASE_INVOICE:REVERSED" to "این فاکتور برگشت خورده است.",
        "ITEM:HAS_STOCK" to "این کالا هنوز در انبار موجودی دارد؛ ابتدا موجودی را مصرف، انتقال یا انبارگردانی کنید.",
        "ITEM:USED_IN_RECIPE" to "این کالا در رسپی فعلی یک آیتم منو یا قلم آماده است؛ ابتدا رسپی را عوض کنید.",
        "ITEM:NOT_PREPARED" to "این کالا «تولید داخلی» نیست.",
    )

    fun of(error: Throwable): String = when (error) {
        is DomainException -> of(error.error)
        else -> "عملیات انجام نشد. دوباره تلاش کنید؛ اگر تکرار شد از داده‌ها پشتیبان بگیرید و با پشتیبانی تماس بگیرید."
    }

    fun of(error: DomainError): String = when (error) {
        is DomainError.InvalidState -> states["${error.entity}:${error.state.substringBefore(':')}"] ?: error.userMessage
        // Usually a form restored after the app was closed, whose first submission had already gone through.
        is DomainError.IdempotencyConflict -> "این فرم قبلاً ثبت شده است (احتمالاً پیش از بسته شدن برنامه). فهرست را بررسی کنید؛ برای ثبت مورد تازه، صفحه را از نو باز کنید."
        is DomainError.InsufficientFunds -> "موجودی حساب کافی نیست (موجود: ${Fa.rial(error.available)} ریال)."
        is DomainError.InsufficientStock -> "موجودی انبار کافی نیست (موجود: ${Fa.quantity(ir.sabou.kernel.Quantity.of(error.available))})."
        else -> error.userMessage
    }
}
