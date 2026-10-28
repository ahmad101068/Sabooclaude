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
        // ADR-0029 .. 0038
        "COUNT_PLAN:DUPLICATE_NAME" to "برنامه‌ی شمارشی با این نام در این شعبه هست.",
        "COUNT_PLAN:NOT_FOR_THIS_LOCATION" to "این برنامه‌ی شمارش برای این انبار نیست یا غیرفعال است.",
        "BANK_STATEMENT:ALREADY_IMPORTED" to "این صورت‌حساب (یا بیشتر سطرهایش) قبلاً وارد شده است.",
        "TREASURY_ACCOUNT:NOT_BANK" to "صورت‌حساب فقط برای حساب بانکی وارد می‌شود.",
        "STATEMENT_LINE:AMOUNT_DIFFERS" to "مبلغ یا جهت این ردیف با سطر صورت‌حساب یکی نیست.",
        "STATEMENT_LINE:ALREADY_MATCHED" to "این سطر صورت‌حساب قبلاً جور شده است.",
        "TREASURY_MOVEMENT:ALREADY_MATCHED" to "این ردیف دفتر با سطر دیگری جور شده است.",
        "TREASURY_MOVEMENT:NOT_MATCHABLE" to "این ردیف برگشت خورده یا متعلق به حساب دیگری است.",
        // Two phones (ADR-0042)
        "DEVICE:HUB_ONLY" to "این کار فقط روی گوشی اصلی انجام می‌شود.",
        "DEVICE:PAIRING_LOST" to "اتصال این گوشی به گوشی اصلی از بین رفته؛ مالک باید آن را دوباره وصل یا جدا کند. تا آن موقع ثبتی انجام نمی‌شود.",
        "SYNC:COPY_SIZE" to "اندازه‌ی نسخه‌ای که گوشی اصلی فرستاد غیرعادی است؛ دریافت نشد.",
        "SYNC:NO_ROOM" to "فضای خالی این گوشی برای دریافت داده‌های گوشی اصلی کافی نیست؛ کمی فضا آزاد کنید و دوباره امتحان کنید.",
        "SYNC:CURSOR_AHEAD" to "این گوشی با گوشی اصلی هماهنگ نیست؛ دوباره وصل شود.",
        "DEVICE:HUB_UNREACHABLE" to "گوشی اصلی در دسترس نیست. هر دو گوشی باید به یک وای‌فای وصل باشند و برنامه روی گوشی اصلی باز یا در حال سرویس باشد. فرم پاک نمی‌شود؛ دوباره بزنید.",
        "DEVICE:VERSION" to "نسخه‌ی برنامه روی دو گوشی یکی نیست؛ هر دو گوشی را با یک فایل نصب به‌روز کنید.",
        "DEVICE:NOT_PAIRED" to "این گوشی به گوشی اصلی وصل نیست.",
        "DEVICE:HUB_NOT_READY" to "برنامه روی گوشی اصلی هنوز آماده نیست؛ آن را باز کنید و دوباره تلاش کنید.",
        "DATABASE:SEALED" to "داده‌های این گوشی در حال جایگزینی است؛ چند لحظه بعد دوباره تلاش کنید.",
        "PERIOD:NEEDS_ALL_BRANCHES" to "بستن ماه دفاتر همه‌ی شعب را قفل می‌کند؛ فقط کسی که به همه‌ی شعب دسترسی دارد آن را می‌بیند و انجام می‌دهد.",
        "STOCK_COUNT:STALE" to "از زمان این شمارش، از بعضی کالاها مصرف، ضایعات یا انتقال ثبت شده و اختلاف شمرده‌شده دیگر با موجودی جور نیست. این شمارش را رد کنید و دوباره بشمارید.",
        "PERIOD:CHECKLIST" to "پیش از بستن ماه، موارد «لازم» چک‌لیست را انجام دهید.",
        "PERIOD:ALREADY_CLOSED" to "این ماه قبلاً بسته شده است.",
        "SHIFT:OVERLAP" to "این کارمند در این ساعت شیفت دیگری دارد.",
        "CLOCK_PIN:NOT_SET" to "برای این کارمند رمز ساعت‌زنی تعیین نشده است.",
        "PUNCH:OPEN" to "کسی ورود زده ولی خروج نزده است؛ پیش از تأیید، خروجش را ثبت یا اصلاح کنید.",
        "PUNCH:DUPLICATE" to "همین الان ساعت زده شده است.",
        "PUNCH:NOTHING" to "برای این روز شیفت یا ساعت‌زنی‌ای نیست.",
        "ATTENDANCE:PERIOD_APPROVED" to "حقوق این دوره تأیید شده است؛ حضور و غیاب آن قابل تغییر نیست.",
        "COLLECTION:ALREADY_REVERSED" to "این دریافت قبلاً برگشت خورده است.",
        "CUSTOMER:CREDIT_LIMIT_EXCEEDED" to "مبلغ نسیه از سقف اعتبار مشتری بیشتر است.",
        "CUSTOMER:DUPLICATE" to "مشتری با این نام قبلاً ثبت شده است.",
        "CUSTOMER:INACTIVE" to "این مشتری غیرفعال است.",
        "POS_IMPORT:DAY_ALREADY_IMPORTED" to "فروش این روز از همین صندوق قبلاً وارد شده است؛ برای ورود دوباره، اول فروش واردشده را برگشت بزنید.",
        "POS_IMPORT:NOT_READY" to "پیش از ورود، موارد مانده را برطرف کنید.",
        "POS_IMPORT:FILE_ALREADY_IMPORTED" to "این فایل قبلاً وارد شده است.",
        "POS_IMPORT:INVOICE_ALREADY_IMPORTED" to "بعضی فاکتورهای این فایل قبلاً وارد شده‌اند.",
        "SALES_DAY:HAS_DRAFTS" to "این روز فروش پیش‌نویس دارد؛ آن‌ها را ثبت نهایی کنید یا کنار بگذارید، بعد روز را ببندید.",
        "DAILY_SALE:ALREADY_POSTED" to "فروش این روز قبلاً ثبت نهایی شده است.",
        "DAILY_SALE:HAS_TAX_INVOICE" to "این فروش صورتحساب مالیاتی دارد؛ ابتدا آن را پس بگیرید (اگر ارسال نشده) یا ابطال کنید (اگر پذیرفته شده).",
        "PURCHASE_INVOICE:CORRECTION_BELOW_PAID" to "مبلغ اصلاح‌شده از آنچه پرداخت یا تسویه شده کمتر است؛ ابتدا پرداخت را برگشت بزنید.",
        "PURCHASE_INVOICE:ACCOUNT_LINE_OTHER_BRANCH" to "این ردیف هزینه‌ی شعبه‌ی دیگری است؛ آن را با برگشت و ثبت دوباره اصلاح کنید.",
        "PURCHASE_INVOICE:OPENING_BALANCE" to "مانده‌ی اول دوره با این روش اصلاح نمی‌شود.",
        "STOCK:REVALUATION_EXCEEDS_VALUE" to "ارزش موجودی این کالا برای این اصلاح کافی نیست.",
        "DAILY_SALE:HAS_COLLECTIONS" to "برای نسیه‌های این روز دریافت ثبت شده؛ ابتدا آن دریافت‌ها را برگشت بزنید.",
        "DAILY_SALE:NOT_POSTED" to "فروش این روز هنوز ثبت نهایی نشده است.",
        "DAILY_SALE:SETTLEMENT_MISMATCH" to "جمع روش‌های تسویه با مبلغ قابل تسویه برابر نیست.",
        "EMPLOYEE:DUPLICATE_NATIONAL_ID" to "کارمندی با این کد ملی قبلاً ثبت شده است.",
        "EMPLOYEE:DUPLICATE_NATIONAL_ID_ENDED" to "این کد ملی متعلق به کارمندی است که همکاری‌اش تمام شده؛ در فهرست کارکنان روی کارت او «بازگشت به کار» را بزنید.",
        "EMPLOYEE:STILL_EMPLOYED" to "همکاری این کارمند تمام نشده است.",
        "SALARY:IN_APPROVED_PERIOD" to "حقوق ماهی که این تاریخ در آن است تأیید شده است؛ تغییر حقوق را از بعد از آخرین ماه تأییدشده بگذارید.",
        "PUNCH:ALREADY_VOIDED" to "این ساعت‌زنی قبلاً لغو شده است.",
        "LEAVE:OVERLAP" to "برای یکی از این روزها مرخصی ثبت شده است.",
        "LEAVE:CANCELLED" to "این مرخصی قبلاً لغو شده است.",
        "ADVANCE:AMOUNT_EXCEEDS_OUTSTANDING" to "مبلغ از مانده‌ی بدهی این مساعده بیشتر است.",
        "ADVANCE:ALREADY_REVERSED" to "این مساعده قبلاً لغو شده است.",
        "ADVANCE:PARTLY_REPAID" to "بخشی از این مساعده پس داده شده (از حقوق یا نقدی)؛ دیگر قابل لغو نیست.",
        "EMPLOYEE:TRANSFER_NOT_AT_PAID_END" to "انتقال باید از روز بعد از آخرین ماه حقوق تأییدشده در این شعبه باشد (یا پیش از شروع کار). اول حقوق ماه جاری را در این شعبه محاسبه و تأیید کنید.",
        "EMPLOYEE:TRANSFER_DRAFT_RUN" to "لیست حقوق تأییدنشده‌ای در این شعبه این کارمند را شامل می‌شود؛ اول آن را تأیید یا حذف کنید.",
        "EMPLOYEE:TRANSFER_HAS_LATER_RECORDS" to "برای این کارمند در این شعبه بعد از تاریخ انتقال شیفت یا حضور ثبت شده است؛ اول آن‌ها را بردارید.",
        "EMPLOYEE:ALREADY_ENDED" to "همکاری این کارمند قبلاً پایان یافته است.",
        "EMPLOYEE:PERIOD_APPROVED" to "حقوق کامل ماهی که بعد از این تاریخ تمام می‌شود تأیید شده است؛ تاریخ پایان را آخرین روز آن ماه یا بعد از آن بگذارید.",
        "EMPLOYEE:START_IN_APPROVED_PERIOD" to "حقوق ماهی که این تاریخ شروع در آن است تأیید شده است؛ تاریخ شروع را بعد از آخرین ماه تأییدشده بگذارید.",
        "PAYROLL_RUN:STALE" to "از زمان محاسبه، اطلاعات کارکنان یا حضور و غیاب تغییر کرده است. لیست حقوق را دوباره محاسبه کنید.",
        "EMPLOYEE:NOT_EMPLOYED_ON_DATE" to "این کارمند در این روز مشغول به کار نبوده است.",
        "ITEM:DUPLICATE_NAME" to "کالایی با این نام وجود دارد.",
        "ITEM:DUPLICATE_CODE" to "کالای دیگری همین کد یا بارکد را دارد.",
        "ITEM:UNIT_IN_USE" to "واحد این کالا دیگر عوض نمی‌شود: با آن گردش، رسپی یا بسته‌بندی ثبت شده است. کالای تازه با واحد درست تعریف کنید و این را غیرفعال کنید.",
        "ITEM:IN_USE" to "این کالا در سند، رسپی، قیمت یا برنامه‌ای آمده و حذف نمی‌شود؛ می‌توانید غیرفعالش کنید.",
        "LOCATION:HAS_STOCK" to "در این انبار هنوز کالا هست؛ اول موجودی را منتقل یا انبارگردانی کنید.",
        "LOCATION:HAS_SHORTAGES" to "این انبار کسری مصرف باز دارد؛ اول با خرید، انتقال یا انبارگردانی تسویه شود.",
        "ITEM:INACTIVE" to "این کالا غیرفعال است.",
        "JOURNAL:ALREADY_REVERSED" to "این سند قبلاً برگشت خورده است.",
        "JOURNAL:IS_REVERSAL" to "سند برگشتی را نمی‌توان دوباره برگشت زد.",
        "LOCATION:DUPLICATE_NAME" to "انباری با این نام در این شعبه وجود دارد.",
        "LOCATION:INACTIVE" to "این انبار غیرفعال است.",
        "TRANSFER:ALREADY_RECEIVED" to "شعبه‌ی مقصد این انتقال را تحویل گرفته است؛ برای برگرداندن کالا یک انتقال تازه از آن شعبه ثبت شود.",
        "TRANSFER:CANCELLED" to "این انتقال برگشت خورده است.",
        "SUPPLIER:PAYMENT_EXCEEDS_PAYABLE" to "مبلغ از جمع فاکتورهای تأییدشده و پرداخت‌نشده‌ی این تأمین‌کننده بیشتر است؛ مازاد را پیش‌پرداخت ثبت کنید.",
        "SUPPLIER_ADVANCE:EXCEEDS_AVAILABLE" to "مانده‌ی این پیش‌پرداخت کمتر از این مبلغ است.",
        "SUPPLIER_ADVANCE:IN_USE" to "بخشی از این پیش‌پرداخت با فاکتور تسویه شده است؛ اول آن تسویه را برگردانید.",
        "SUPPLIER_ADVANCE:ALREADY_REVERSED" to "این پیش‌پرداخت قبلاً برگشت خورده است.",
        "CUSTOMER_DEPOSIT:EXCEEDS_AVAILABLE" to "مانده‌ی این پیش‌دریافت کمتر از این مبلغ است.",
        "CUSTOMER_DEPOSIT:IN_USE" to "بخشی از این پیش‌دریافت با طلب تسویه شده است؛ اول آن تسویه را برگردانید.",
        "CUSTOMER_DEPOSIT:ALREADY_REVERSED" to "این پیش‌دریافت قبلاً برگشت خورده است.",
        "ACCOUNT:DUPLICATE_CODE" to "حسابی با این کد هست.",
        "ACCOUNT:DUPLICATE_NAME" to "حسابی با این نام هست.",
        "ACCOUNT:HAS_BALANCE" to "این حساب مانده دارد و بسته نمی‌شود.",
        "ACCOUNT:SYSTEM" to "حساب‌های استاندارد برنامه تغییر نمی‌کنند؛ حساب تازه‌ی خودتان را بسازید.",
        "RECEIVABLE:HAS_COLLECTIONS" to "از این طلب چیزی وصول یا سوخت شده است؛ اول آن را برگردانید.",
        "RECEIVABLE:NOT_OPENING" to "این طلب مانده‌ی اول دوره نیست؛ از فروش خودش برگشت بزنید.",
        "SALES_TAX:MORE_THAN_OWED" to "مبلغ از مالیات و عوارض فروشِ پرداخت‌نشده‌ی این شعبه بیشتر است.",
        "SALES_TAX:CREDIT_MORE_THAN_HELD" to "اعتبار مالیاتی خرید این شعبه کمتر از این مبلغ است.",
        "DAILY_SALE:HAS_WRITE_OFFS" to "بخشی از نسیه‌ی این فروش سوخت شده است؛ فروش برگشت نمی‌خورد.",
        "TREASURY_ACCOUNT:HAS_BALANCE" to "این حساب مانده دارد؛ اول مانده را منتقل کنید.",
        "TREASURY_ACCOUNT:HAS_CHEQUES" to "چک بازی در این حساب هست؛ اول وضعیت چک‌ها را مشخص کنید.",
        "STOCK_REQUEST:NOT_OPEN" to "این درخواست قبلاً ارسال، رد یا لغو شده است.",
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
        "PURCHASE_INVOICE:NEEDS_MATCH_REVIEW" to "این فاکتور با سفارشش جور نیست یا شاید تکراری است؛ پیش از پرداخت، فرد دیگری باید مغایرت را بررسی کند.",
        "PURCHASE_INVOICE:NO_MATCH_REVIEW_NEEDED" to "این فاکتور مغایرتی برای بررسی ندارد یا قبلاً بررسی شده است.",
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
        "MENU_ITEM:IN_USE" to "این آیتم رسپی، قیمت یا فروش دارد و حذف نمی‌شود؛ می‌توانید آن را از منو خارج کنید.",
        "RECIPE:NO_VERSION_ON_DATE" to "برای یکی از اقلام منو در این تاریخ رسپی تعریف نشده است.",
        "SALARY_PAYMENT:ALREADY_REVERSED" to "این پرداخت قبلاً برگشت خورده است.",
        "SALES_DAY:CLOSED" to "این روز فروش بسته و صندوقش شمرده شده است؛ برای ثبت یا برگشت در این تاریخ، اول روز را بازگشایی کنید.",
        "SALES_DAY:NOT_CLOSED" to "این روز فروش بسته نیست.",
        "STOCK_MOVEMENT:ALREADY_REVERSED" to "این گردش انبار قبلاً برگشت خورده است.",
        // Electronic invoices (ADR-0047)
        "TAX_PROFILE:MISSING_MEMORY_ID" to "شناسه‌ی مالیاتی فروشنده یا شناسه‌ی یکتای حافظه‌ی مالیاتی وارد نشده است.",
        "TAX_PROFILE:NO_VAT_RATE" to "برای تاریخ این فروش نرخ مالیات بر ارزش افزوده تعیین نشده است.",
        "TAX_INVOICE:ALREADY_ISSUED" to "برای این فروش قبلاً صورتحساب صادر شده است.",
        "TAX_INVOICE:CANCELLATION_EXISTS" to "ابطال این صورتحساب قبلاً صادر شده است.",
        "TAX_INVOICE:READY" to "این صورتحساب هنوز در سامانه پذیرفته نشده است.",
        "TAX_INVOICE:SENT" to "نتیجه‌ی این صورتحساب هنوز از سامانه نیامده است؛ استعلام کنید.",
        "TAX_INVOICE:REJECTED" to "این صورتحساب در سامانه رد شده است.",
        "TAX_INVOICE:CANCELLED" to "این صورتحساب ابطال شده است.",
        "SALE:NOT_ONE_CREDIT_CUSTOMER" to "صورتحساب با مشخصات خریدار برای فروشی صادر می‌شود که (تمام یا بخشی از آن) نسیه‌ی یک مشتری است.",
        "CUSTOMER:NO_TAX_ID" to "کد ملی، شناسه‌ی ملی یا کد اقتصادی این مشتری ثبت نشده است.",
        "SALE:SERVICE_CHARGE" to "فروش با حق سرویس جداگانه در صورتحساب نمی‌آید؛ سرویس را به‌صورت یک آیتم منو با شناسه‌ی خدمت ثبت کنید.",
        "SALE:DRAFT" to "این فروش هنوز ثبت نهایی نشده است.",
        "SALE:REVERSED" to "این فروش برگشت خورده است.",
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
        // Usually a form restored after the app was closed, whose first submission had already gone through.
        is DomainError.IdempotencyConflict -> "این فرم قبلاً ثبت شده است (احتمالاً پیش از بسته شدن برنامه). فهرست را بررسی کنید؛ برای ثبت مورد تازه، صفحه را از نو باز کنید."
        is DomainError.InsufficientFunds -> "موجودی حساب کافی نیست (موجود: ${Fa.rial(error.available)} ریال)."
        is DomainError.InsufficientStock -> "موجودی انبار ${error.itemName.takeIf { it.isNotBlank() }?.let { "«$it» " } ?: ""}کافی نیست: " +
            "لازم ${Fa.quantity(ir.sabou.kernel.Quantity.of(error.requested))}، موجود ${Fa.quantity(ir.sabou.kernel.Quantity.of(error.available))}."
        is DomainError.InvalidState -> if (error.entity == "USER" && error.state.startsWith("LOCKED:")) {
            val seconds = error.state.substringAfter(':').toLongOrNull() ?: 0
            "به دلیل رمزهای اشتباه، ورود ${waitText(seconds)} قفل است."
        } else if (error.entity == "CLOCK_PIN" && error.state.startsWith("LOCKED:")) {
            val seconds = error.state.substringAfter(':').toLongOrNull() ?: 0
            "به دلیل رمزهای اشتباه، ساعت‌زنی این نفر ${waitText(seconds)} قفل است."
        } else if (error.entity == "LEAVE" && error.state.startsWith("NOT_ENOUGH:")) {
            val minutes = error.state.substringAfter(':').toLongOrNull() ?: 0
            "مانده‌ی مرخصی استحقاقی کافی نیست؛ مانده: ${Fa.number(minutes / 60)} ساعت و ${Fa.number(minutes % 60)} دقیقه."
        } else if (error.entity == "MENU_ITEM" && (error.state.startsWith("NO_PRODUCT_ID:") || error.state.startsWith("NO_UNIT_CODE:"))) {
            val what = if (error.state.startsWith("NO_PRODUCT_ID:")) "شناسه‌ی کالا/خدمت" else "کد واحد اندازه‌گیری"
            "«${error.state.substringAfter(':')}» $what ندارد؛ آن را در «من › حقوق، مالیات و بودجه › اطلاعات مالیاتی» وارد کنید."
        } else if (error.entity == "SALE" && error.state.startsWith("VAT_MISMATCH:")) {
            "مالیات بر ارزش افزوده‌ی ثبت‌شده روی این فروش با نرخ قانونی اقلامش (${Fa.rial(error.state.substringAfter(':').toLongOrNull() ?: 0)} ریال) جور نیست؛ فروش را اصلاح کنید."
        } else states["${error.entity}:${error.state.substringBefore(':')}"] ?: error.userMessage
        else -> error.userMessage
    }

    /** «حدود ۳ دقیقه دیگر» / «۴۵ ثانیه دیگر». */
    private fun waitText(seconds: Long): String = when {
        seconds <= 0 -> "چند لحظه"
        seconds < 60 -> "${Fa.number(seconds)} ثانیهٔ دیگر"
        else -> "حدود ${Fa.number((seconds + 59) / 60)} دقیقهٔ دیگر"
    }
}
