package ledger

import (
	"encoding/base64"
	"encoding/csv"
	"encoding/json"
	"io"
	"math/big"
	"strings"
	"time"
	"unicode/utf8"
)

type ImportMapping struct {
	Account      string  `json:"account_id"`
	DateColumn   string  `json:"date_column"`
	AmountColumn string  `json:"amount_column"`
	PayeeColumn  *string `json:"payee_column"`
	DateFormat   string  `json:"date_format"`
	Timezone     string  `json:"timezone"`
	Delimiter    string  `json:"delimiter"`
	Encoding     string  `json:"encoding"`
	Decimal      string  `json:"decimal_separator"`
	Convention   string  `json:"amount_convention"`
}
type ImportInput struct {
	ID      string        `json:"id"`
	Format  string        `json:"format"`
	Content string        `json:"content_base64"`
	Mapping ImportMapping `json:"mapping"`
}
type ImportError struct {
	Path    string `json:"path"`
	Code    string `json:"code"`
	Message string `json:"message"`
}
type ImportRow struct {
	SourceID   *string       `json:"source_transaction_id"`
	Number     int           `json:"row_number"`
	Status     string        `json:"status"`
	Errors     []ImportError `json:"errors"`
	Candidates []string      `json:"candidate_transaction_ids"`
	Amount     *string       `json:"amount_minor"`
	Occurred   *string       `json:"occurred_at"`
	Payee      string        `json:"payee"`
}
type ParsedCSV struct {
	Currency   string
	Row        ImportRow
	Note       string
	OriginalID string
}

func DecodeImport(body []byte) (ImportInput, error) {
	var v ImportInput
	if err := decodeObjectLimit(body, &v, []string{"id", "format", "content_base64", "mapping"}, nil, 14*1024*1024); err != nil {
		return v, err
	}
	var raw struct {
		Mapping json.RawMessage `json:"mapping"`
	}
	json.Unmarshal(body, &raw)
	if err := decodeObject(raw.Mapping, &v.Mapping, []string{"account_id", "date_column", "amount_column", "payee_column", "date_format", "timezone", "delimiter", "encoding", "decimal_separator", "amount_convention"}, []string{"payee_column"}); err != nil {
		return v, err
	}
	m := v.Mapping
	if !uuid(v.ID) || !uuid(m.Account) || v.Format != "csv" || m.Encoding != "UTF-8" || m.Convention != "signed_income_positive" || m.Decimal != "." && m.Decimal != "," || m.Delimiter != "," && m.Delimiter != ";" && m.Delimiter != "\t" || m.DateFormat != "YYYY-MM-DD" && m.DateFormat != "RFC3339" || m.DateColumn == "" || m.AmountColumn == "" {
		return v, invalid()
	}
	if _, err := time.LoadLocation(m.Timezone); err != nil {
		return v, invalid()
	}
	if len(v.Content) > 13981016 {
		return v, invalid()
	}
	v.ID = strings.ToLower(v.ID)
	v.Mapping.Account = strings.ToLower(v.Mapping.Account)
	return v, nil
}
func CSVAmount(value, separator string, scale int) (string, error) {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "+") {
		value = value[1:]
	}
	negative := strings.HasPrefix(value, "-")
	if negative {
		value = value[1:]
	}
	parts := strings.Split(value, separator)
	if len(parts) > 2 || parts[0] == "" {
		return "", invalid()
	}
	fraction := ""
	if len(parts) == 2 {
		fraction = parts[1]
		if fraction == "" {
			return "", invalid()
		}
	}
	if len(fraction) > scale {
		return "", invalid()
	}
	for _, part := range parts {
		for _, r := range part {
			if r < '0' || r > '9' {
				return "", invalid()
			}
		}
	}
	n, ok := new(big.Int).SetString(parts[0]+fraction+strings.Repeat("0", scale-len(fraction)), 10)
	if !ok || n.Sign() == 0 || n.Cmp(big.NewInt(MaxMinor)) > 0 {
		return "", invalid()
	}
	if negative {
		n.Neg(n)
	}
	return n.String(), nil
}
func ParseCSV(v ImportInput, scale int) ([]ParsedCSV, []byte, error) {
	content, err := base64.StdEncoding.Strict().DecodeString(v.Content)
	if err != nil || len(content) > 10*1024*1024 || !utf8.Valid(content) {
		return nil, nil, invalid()
	}
	content = []byte(strings.TrimPrefix(string(content), "\ufeff"))
	reader := csv.NewReader(strings.NewReader(string(content)))
	reader.Comma = []rune(v.Mapping.Delimiter)[0]
	header, err := reader.Read()
	if err != nil {
		return nil, nil, invalid()
	}
	columns := map[string]int{}
	for index, name := range header {
		if name == "" {
			return nil, nil, invalid()
		}
		if _, exists := columns[name]; exists {
			return nil, nil, invalid()
		}
		columns[name] = index
	}
	for _, name := range []string{v.Mapping.DateColumn, v.Mapping.AmountColumn} {
		if _, ok := columns[name]; !ok {
			return nil, nil, invalid()
		}
	}
	if v.Mapping.PayeeColumn != nil {
		if _, ok := columns[*v.Mapping.PayeeColumn]; !ok {
			return nil, nil, invalid()
		}
	}
	loc, _ := time.LoadLocation(v.Mapping.Timezone)
	rows := []ParsedCSV{}
	for rowNumber := 2; ; rowNumber++ {
		fields, err := reader.Read()
		if err == io.EOF {
			break
		}
		if rowNumber > 1001 {
			return nil, nil, invalid()
		}
		if err != nil {
			return nil, nil, invalid()
		}
		row := ImportRow{Number: rowNumber, Status: "valid", Errors: []ImportError{}, Candidates: []string{}}
		bad := func(field string) {
			row.Status = "invalid"
			row.Errors = append(row.Errors, ImportError{Path: field, Code: "validation_error", Message: "Некорректное значение CSV."})
		}
		amount, err := CSVAmount(fields[columns[v.Mapping.AmountColumn]], v.Mapping.Decimal, scale)
		if err != nil {
			bad("amount")
		} else {
			row.Amount = &amount
		}
		rawDate := fields[columns[v.Mapping.DateColumn]]
		var date time.Time
		if v.Mapping.DateFormat == "YYYY-MM-DD" {
			date, err = time.ParseInLocation("2006-01-02", rawDate, loc)
		} else {
			date, err = time.Parse(time.RFC3339Nano, rawDate)
		}
		if err != nil || v.Mapping.DateFormat == "YYYY-MM-DD" && date.Format("2006-01-02") != rawDate || date.Year() < 1900 || date.Year() > 9998 {
			bad("date")
		} else {
			value := date.UTC().Truncate(time.Microsecond).Format(time.RFC3339Nano)
			row.Occurred = &value
		}
		if v.Mapping.PayeeColumn != nil {
			row.Payee = fields[columns[*v.Mapping.PayeeColumn]]
			if !validText(row.Payee, 200) {
				bad("payee")
			}
		}
		parsed := ParsedCSV{Row: row}
		if index, ok := columns["note"]; ok {
			parsed.Note = fields[index]
			if !validText(parsed.Note, 2000) {
				bad("note")
				parsed.Row = row
			}
		}
		if index, ok := columns["source_transaction_id"]; ok && fields[index] != "" {
			parsed.OriginalID = strings.ToLower(fields[index])
			row.SourceID = &parsed.OriginalID
			parsed.Row = row
			if !uuid(parsed.OriginalID) {
				bad("source_transaction_id")
				parsed.Row = row
			}
		}
		if index, ok := columns["kind"]; ok && fields[index] != "expense" && fields[index] != "income" && parsed.OriginalID == "" {
			bad("kind")
			parsed.Row = row
		}
		if index, ok := columns["text_escaped"]; ok && fields[index] == "1" {
			parsed.Row.Payee = strings.TrimPrefix(parsed.Row.Payee, "'")
			parsed.Note = strings.TrimPrefix(parsed.Note, "'")
		}
		if index, ok := columns["currency"]; ok {
			parsed.Currency = fields[index]
		}
		if index, ok := columns["status"]; ok && fields[index] != "posted" && parsed.OriginalID == "" {
			bad("status")
			parsed.Row = row
		}
		if index, ok := columns["kind"]; ok && row.Amount != nil {
			if fields[index] == "expense" && !strings.HasPrefix(*row.Amount, "-") || fields[index] == "income" && strings.HasPrefix(*row.Amount, "-") {
				bad("kind")
				parsed.Row = row
			}
		}
		rows = append(rows, parsed)
	}
	if len(rows) == 0 {
		return nil, nil, invalid()
	}
	return rows, content, nil
}
func minorDecimal(value string, scale int) string {
	n, _ := new(big.Int).SetString(value, 10)
	digits := n.Abs(n).String()
	if len(digits) <= scale {
		digits = strings.Repeat("0", scale+1-len(digits)) + digits
	}
	out := digits
	if scale > 0 {
		out = digits[:len(digits)-scale] + "." + digits[len(digits)-scale:]
	}
	if strings.HasPrefix(value, "-") {
		out = "-" + out
	}
	return out
}
