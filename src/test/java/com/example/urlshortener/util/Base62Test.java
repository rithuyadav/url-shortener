package com.example.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

class Base62Test {

    @Test
    void randomString_hasRequestedLength() {
        assertThat(Base62.randomString(7)).hasSize(7);
        assertThat(Base62.randomString(1)).hasSize(1);
        assertThat(Base62.randomString(20)).hasSize(20);
    }

    @RepeatedTest(20)
    void randomString_onlyContainsBase62Alphabet() {
        String s = Base62.randomString(10);
        assertThat(s).matches("^[0-9a-zA-Z]{10}$");
    }

    @Test
    void randomString_isNotTriviallyPredictable() {
        // Not a rigorous randomness test - just guards against an accidental constant/counter implementation.
        String a = Base62.randomString(12);
        String b = Base62.randomString(12);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void isValidAlphabetOnly_rejectsNullAndEmptyAndNonAlphabetChars() {
        assertThat(Base62.isValidAlphabetOnly(null)).isFalse();
        assertThat(Base62.isValidAlphabetOnly("")).isFalse();
        assertThat(Base62.isValidAlphabetOnly("abc-123")).isFalse();
        assertThat(Base62.isValidAlphabetOnly("abc123XYZ")).isTrue();
    }
}
