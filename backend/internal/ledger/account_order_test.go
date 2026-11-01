package ledger

import "testing"

func TestAccountOrderInput(t *testing.T) {
	for _, body := range []string{
		`{"expected_version":"0","account_ids":["a0000000-0000-4000-8000-000000000001"]}`,
		`{"expected_version":"12","account_ids":["a0000000-0000-4000-8000-000000000001","a0000000-0000-4000-8000-000000000002"]}`,
	} {
		if _, err := DecodeAccountOrder([]byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	for _, body := range []string{
		`{"expected_version":"-1","account_ids":["a0000000-0000-4000-8000-000000000001"]}`,
		`{"expected_version":"01","account_ids":["a0000000-0000-4000-8000-000000000001"]}`,
		`{"expected_version":"9223372036854775808","account_ids":["a0000000-0000-4000-8000-000000000001"]}`,
		`{"expected_version":"0","account_ids":[]}`,
		`{"expected_version":"0","account_ids":null}`,
		`{"expected_version":"0","account_ids":["bad"]}`,
		`{"expected_version":"0","account_ids":["a0000000-0000-4000-8000-000000000001","A0000000-0000-4000-8000-000000000001"]}`,
		`{"account_ids":["a0000000-0000-4000-8000-000000000001"]}`,
		`{"expected_version":"0","account_ids":["a0000000-0000-4000-8000-000000000001"],"unknown":true}`,
	} {
		if _, err := DecodeAccountOrder([]byte(body)); err == nil {
			t.Fatal("invalid order accepted")
		}
	}
}
