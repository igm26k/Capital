package httpapi

import (
	commands "accounting/backend/internal/sync"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

func MountPull(mux *http.ServeMux, reader *commands.PullReader) {
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/sync/changes", func(w http.ResponseWriter, r *http.Request) {
		token, _, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		workspace := strings.ToLower(r.PathValue("workspace_id"))
		if !validUUID(workspace) {
			reject(w, fail(400, "bad_request"))
			return
		}
		q, e := url.ParseQuery(r.URL.RawQuery)
		if e != nil {
			reject(w, fail(400, "bad_request"))
			return
		}
		for k, v := range q {
			if len(v) != 1 || k != "cursor" && k != "limit" {
				reject(w, fail(400, "bad_request"))
				return
			}
		}
		limit := 50
		if q.Has("limit") {
			limit, e = strconv.Atoi(q.Get("limit"))
			if e != nil {
				reject(w, fail(400, "bad_request"))
				return
			}
		}
		page, e := reader.Read(r.Context(), token, workspace, q.Get("cursor"), limit)
		if e != nil {
			reject(w, e)
			return
		}
		respond(w, 200, page)
	})
}
