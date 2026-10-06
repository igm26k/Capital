// Package ledger implements exact financial values and transactional accounting.
package ledger

import (
	"errors"
	"math/big"
	"strconv"
)

const MaxMinor int64 = 9_000_000_000_000_000

var (
	ErrMoneyFormat = errors.New("money must be a canonical decimal integer")
	ErrMoneyRange  = errors.New("money amount out of range")
)

// Money is an individual input or account balance in currency minor units.
// Its zero value is valid; other values can only be constructed after validation.
type Money struct{ minor int64 }

func ParseMoney(s string) (Money, error) {
	if !canonicalInteger(s) {
		return Money{}, ErrMoneyFormat
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return Money{}, ErrMoneyRange
	}
	return NewMoney(n)
}

func NewMoney(n int64) (Money, error) {
	if n < -MaxMinor || n > MaxMinor {
		return Money{}, ErrMoneyRange
	}
	return Money{minor: n}, nil
}

func ParsePositiveMoney(s string) (Money, error) {
	m, err := ParseMoney(s)
	if err != nil {
		return Money{}, err
	}
	if m.minor <= 0 {
		return Money{}, ErrMoneyRange
	}
	return m, nil
}

func (m Money) Minor() int64                 { return m.minor }
func (m Money) String() string               { return strconv.FormatInt(m.minor, 10) }
func (m Money) MarshalJSON() ([]byte, error) { return []byte(`"` + m.String() + `"`), nil }
func (m Money) Negate() Money                { return Money{minor: -m.minor} }

// SumMoney checks the final balance, not intermediate partial sums. Thus row
// ordering cannot change validity, and int64 overflow cannot lose cancellations.
func SumMoney(values ...Money) (Money, error) {
	var sum, term big.Int
	for _, value := range values {
		term.SetInt64(value.minor)
		sum.Add(&sum, &term)
	}
	if !sum.IsInt64() {
		return Money{}, ErrMoneyRange
	}
	return NewMoney(sum.Int64())
}

// AggregateMoney permits reports spanning accounts to exceed the account limit.
// The canonical decimal representation avoids mutable big.Int aliasing.
type AggregateMoney struct{ decimal string }

func ParseAggregateMoney(s string) (AggregateMoney, error) {
	if !canonicalInteger(s) {
		return AggregateMoney{}, ErrMoneyFormat
	}
	digits := len(s)
	if s[0] == '-' {
		digits--
	}
	if digits > 40 {
		return AggregateMoney{}, ErrMoneyRange
	}
	return AggregateMoney{decimal: s}, nil
}
func (m AggregateMoney) String() string {
	if m.decimal == "" {
		return "0"
	}
	return m.decimal
}
func (m AggregateMoney) MarshalJSON() ([]byte, error) { return []byte(`"` + m.String() + `"`), nil }
func SumAggregateMoney(values ...AggregateMoney) (AggregateMoney, error) {
	var sum big.Int
	for _, value := range values {
		term, _ := new(big.Int).SetString(value.String(), 10)
		sum.Add(&sum, term)
	}
	return ParseAggregateMoney(sum.String())
}

// Balances validates posted, pending, and projected independently.
func Balances(posted, pending []Money) (Money, Money, Money, error) {
	p, err := SumMoney(posted...)
	if err != nil {
		return Money{}, Money{}, Money{}, err
	}
	d, err := SumMoney(pending...)
	if err != nil {
		return Money{}, Money{}, Money{}, err
	}
	projected, err := SumMoney(p, d)
	if err != nil {
		return Money{}, Money{}, Money{}, err
	}
	return p, d, projected, nil
}

func canonicalInteger(s string) bool {
	if s == "0" {
		return true
	}
	if len(s) == 0 {
		return false
	}
	start := 0
	if s[0] == '-' {
		start = 1
	}
	if start == len(s) || s[start] < '1' || s[start] > '9' {
		return false
	}
	for i := start + 1; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}
