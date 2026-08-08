from scamshield_ml.pii_scrub import scrub


def test_scrubs_a_bare_10_digit_mobile_number():
    assert scrub("call me on 9876543210 now") == "call me on <PHONE> now"


def test_scrubs_a_91_prefixed_mobile_number():
    assert scrub("+91-9876543210") == "<PHONE>"
    assert scrub("919876543210") == "<PHONE>"


def test_scrubs_a_rupee_amount():
    assert scrub("Rs.2,500.00 debited") == "<AMT> debited"
    assert scrub("₹500 credited") == "<AMT> credited"
    assert scrub("INR 45000 received") == "<AMT> received"


def test_scrubs_a_long_account_number_not_matched_by_phone_or_amount():
    assert scrub("A/c XX123456789012 credited") == "A/c XX<ACCT> credited"


def test_short_numbers_are_left_alone():
    # OTPs, PINs, and other short codes are not PII in the same sense and are themselves
    # sometimes signal (design.md section 2.1) -- do not scrub them away.
    assert scrub("your OTP is 482913") == "your OTP is 482913"


def test_a_message_with_no_pii_is_unchanged():
    text = "Your order has been delivered. Thank you for shopping with us."
    assert scrub(text) == text


def test_scrubs_a_salutation_anchored_personal_name():
    assert scrub("Dear Rahul, your OTP is 482913") == "Dear <NAME>, your OTP is 482913"
    assert scrub("Hi Priya Sharma, welcome") == "Hi <NAME>, welcome"
    assert scrub("Mr. Kumar, please verify") == "Mr. <NAME>, please verify"


def test_does_not_scrub_generic_addressees_as_names():
    # "Dear Customer" is not a personal name and must survive untouched.
    assert scrub("Dear Customer, your bill is due") == "Dear Customer, your bill is due"
    assert scrub("Hello Sir, your request is noted") == "Hello Sir, your request is noted"


def test_does_not_mistake_an_all_caps_brand_after_a_salutation_for_a_name():
    # Title-case-only anchoring means all-caps brands are left alone.
    assert scrub("Dear SBI user, update KYC") == "Dear SBI user, update KYC"


def test_salutation_match_requires_a_word_boundary():
    # "hi" inside "Delhi" (and similar) must not trigger the salutation name pass.
    assert scrub("Priya lives in Delhi Ram Nagar") == "Priya lives in Delhi Ram Nagar"
    assert scrub("meet at Ranchi Kumar street") == "meet at Ranchi Kumar street"


def test_multiple_pii_items_in_one_message():
    text = "Rs.2,500.00 debited from A/c XX998877665544. Not you? Call 9876543210."
    scrubbed = scrub(text)
    assert "<AMT>" in scrubbed
    assert "<ACCT>" in scrubbed
    assert "<PHONE>" in scrubbed
    assert "2,500" not in scrubbed
    assert "9876543210" not in scrubbed
