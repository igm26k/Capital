package ledger

import (
	commands "accounting/backend/internal/sync"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func TestExactCSVParsing(t *testing.T) {
	for _, v := range []struct {
		in, sep string
		scale   int
		out     string
	}{{"-12,345", ",", 3, "-12345"}, {"+0.01", ".", 2, "1"}, {"001.20", ".", 2, "120"}, {"9000000000000000", ".", 0, "9000000000000000"}} {
		got, err := CSVAmount(v.in, v.sep, v.scale)
		if err != nil || got != v.out {
			t.Fatal(v, got, err)
		}
	}
	for _, v := range []string{"0", "NaN", "1e3", "1,000.00", "1.001", "90000000000000.01", "--1", "1."} {
		if _, err := CSVAmount(v, ".", 2); err == nil {
			t.Fatal("invalid exact money accepted", v)
		}
	}
	payee := "payee"
	v := ImportInput{Content: base64.StdEncoding.EncodeToString([]byte("date;amount;payee;note\n2026-03-29;-1,234;Тест;'=1+1\n2026-02-30;bad;x;x\n")), Mapping: ImportMapping{DateColumn: "date", AmountColumn: "amount", PayeeColumn: &payee, DateFormat: "YYYY-MM-DD", Timezone: "Europe/Berlin", Delimiter: ";", Decimal: ","}}
	rows, _, err := ParseCSV(v, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || rows[0].Row.Status != "valid" || *rows[0].Row.Amount != "-1234" || *rows[0].Row.Occurred != "2026-03-28T23:00:00Z" || rows[1].Row.Status != "invalid" {
		t.Fatal("incorrect CSV normalization", rows)
	}
	v.Content = base64.StdEncoding.EncodeToString([]byte("date;amount;payee;note;text_escaped\n2026-03-29;-1,234;'=cmd;'\"text;1\n"))
	if got := csvSafeText("=1+1"); got != "'=1+1" {
		t.Fatal("unsafe spreadsheet text", got)
	}
	if got := minorDecimal("-12345", 3); got != "-12.345" {
		t.Fatal("scale lost", got)
	}
}

func TestCSVUploadLimitIsSeparateFromFinancialCommands(t *testing.T) {
	const id = "12345678-1234-4234-8234-123456789012"
	content := "date,amount,padding\n2026-09-01,-1.00," + strings.Repeat("x", 8*1024*1024) + "\n"
	v := ImportInput{ID: id, Format: "csv", Content: base64.StdEncoding.EncodeToString([]byte(content)), Mapping: ImportMapping{Account: id, DateColumn: "date", AmountColumn: "amount", DateFormat: "YYYY-MM-DD", Timezone: "UTC", Delimiter: ",", Encoding: "UTF-8", Decimal: ".", Convention: "signed_income_positive"}}
	body, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := DecodeImport(body)
	if err != nil {
		t.Fatal("supported large upload rejected", err)
	}
	rows, _, err := ParseCSV(parsed, 2)
	if err != nil || len(rows) != 1 || rows[0].Row.Status != "valid" {
		t.Fatal("large CSV parse failed", err)
	}
	if _, err = commands.RequestHashLimit("POST", "/api/v1/workspaces/"+id+"/imports", id, body, 14*1024*1024); err != nil {
		t.Fatal("large request receipt hash rejected", err)
	}
	if _, err = commands.RequestHash("POST", "/api/v1/workspaces/"+id+"/transactions", id, body); err == nil {
		t.Fatal("financial command limit widened")
	}
	v.Content = base64.StdEncoding.EncodeToString([]byte(strings.Repeat("x", 10*1024*1024+1)))
	body, _ = json.Marshal(v)
	if _, err = DecodeImport(body); err == nil {
		if _, _, err = ParseCSV(v, 2); err == nil {
			t.Fatal("oversize CSV accepted")
		}
	}
}
