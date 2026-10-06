package sync

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

var uuidPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var pathUUID = regexp.MustCompile(`(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`)
var integerPattern = regexp.MustCompile(`^-?(0|[1-9][0-9]*)$`)

func validUnicode(data []byte) bool {
	if !utf8.Valid(data) {
		return false
	}
	for i := 0; i < len(data); i++ {
		if data[i] != '\\' {
			continue
		}
		i++
		if i >= len(data) {
			return false
		}
		if data[i] != 'u' {
			continue
		}
		if i+4 >= len(data) {
			return false
		}
		n, e := strconv.ParseUint(string(data[i+1:i+5]), 16, 16)
		if e != nil {
			return false
		}
		i += 4
		if n >= 0xd800 && n <= 0xdbff {
			if i+6 >= len(data) || string(data[i+1:i+3]) != "\\u" {
				return false
			}
			low, e := strconv.ParseUint(string(data[i+3:i+7]), 16, 16)
			if e != nil || low < 0xdc00 || low > 0xdfff {
				return false
			}
			i += 6
		} else if n >= 0xdc00 && n <= 0xdfff {
			return false
		}
	}
	return true
}
func quote(s string) string {
	var out strings.Builder
	out.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"', '\\':
			out.WriteByte('\\')
			out.WriteRune(r)
		case '\b':
			out.WriteString(`\b`)
		case '\f':
			out.WriteString(`\f`)
		case '\n':
			out.WriteString(`\n`)
		case '\r':
			out.WriteString(`\r`)
		case '\t':
			out.WriteString(`\t`)
		default:
			if r < 32 {
				fmt.Fprintf(&out, `\u%04x`, r)
			} else {
				out.WriteRune(r)
			}
		}
	}
	out.WriteByte('"')
	return out.String()
}
func canonicalValue(d *json.Decoder, depth int) (string, error) {
	if depth > 64 {
		return "", errors.New("JSON nesting too deep")
	}
	token, e := d.Token()
	if e != nil {
		return "", e
	}
	switch v := token.(type) {
	case json.Delim:
		switch v {
		case '{':
			values := map[string]string{}
			for d.More() {
				key, e := d.Token()
				if e != nil {
					return "", e
				}
				s, ok := key.(string)
				if !ok {
					return "", errors.New("invalid object key")
				}
				if _, ok := values[s]; ok {
					return "", errors.New("duplicate object key")
				}
				value, e := canonicalValue(d, depth+1)
				if e != nil {
					return "", e
				}
				values[s] = value
			}
			if _, e = d.Token(); e != nil {
				return "", e
			}
			keys := make([]string, 0, len(values))
			for k := range values {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			parts := make([]string, 0, len(keys))
			for _, k := range keys {
				parts = append(parts, quote(k)+":"+values[k])
			}
			return "{" + strings.Join(parts, ",") + "}", nil
		case '[':
			values := []string{}
			for d.More() {
				value, e := canonicalValue(d, depth+1)
				if e != nil {
					return "", e
				}
				values = append(values, value)
			}
			if _, e = d.Token(); e != nil {
				return "", e
			}
			return "[" + strings.Join(values, ",") + "]", nil
		}
	case string:
		return quote(v), nil
	case json.Number:
		if !integerPattern.MatchString(string(v)) {
			return "", errors.New("only integer JSON numbers allowed")
		}
		if v == "-0" {
			return "0", nil
		}
		return string(v), nil
	case bool:
		if v {
			return "true", nil
		}
		return "false", nil
	case nil:
		return "null", nil
	}
	return "", errors.New("invalid JSON value")
}
func CanonicalJSON(body []byte) ([]byte, error) {
	if len(body) > 1048576 || !json.Valid(body) || !validUnicode(body) {
		return nil, errors.New("invalid command JSON")
	}
	d := json.NewDecoder(bytes.NewReader(body))
	d.UseNumber()
	v, e := canonicalValue(d, 0)
	if e != nil {
		return nil, e
	}
	if _, e = d.Token(); e != io.EOF {
		return nil, errors.New("trailing JSON")
	}
	if !strings.HasPrefix(v, "{") {
		return nil, errors.New("command body must be object")
	}
	return []byte(v), nil
}
func RequestHash(method, path, generation string, body []byte) ([32]byte, error) {
	if method != "POST" && method != "PUT" && method != "DELETE" {
		return [32]byte{}, errors.New("invalid command method")
	}
	if !strings.HasPrefix(path, "/api/v1/") || strings.ContainsAny(path, "?#%\r\n") || !uuidPattern.MatchString(generation) {
		return [32]byte{}, errors.New("invalid command path or generation")
	}
	canonical, e := CanonicalJSON(body)
	if e != nil {
		return [32]byte{}, e
	}
	path = pathUUID.ReplaceAllStringFunc(path, strings.ToLower)
	return sha256.Sum256([]byte(method + "\n" + path + "\n" + strings.ToLower(generation) + "\n" + string(canonical))), nil
}
