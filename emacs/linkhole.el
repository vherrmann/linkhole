;;; linkhole.el --- Client for linkhole link queue  -*- lexical-binding: t; -*-

;; Version: 0.1.0
;; Package-Requires: ((emacs "28.1"))
;; Keywords: convenience, hypermedia

;;; Commentary:

;; Push links to a linkhole server while going through an inbox, and
;; list/manage the queue.  Uses only built-in libraries.
;;
;;   M-x linkhole-add            prompt for a URL (and note)
;;   M-x linkhole-add-at-point   org link / URL at point
;;   M-x linkhole-add-region     every URL in the region
;;   M-x linkhole-list           browse and manage the queue
;;
;; Configure `linkhole-server-url'.  The bearer token is looked up with
;; `auth-source' (host = server host); `linkhole-token' is the fallback.

;;; Code:

(require 'auth-source)
(require 'cl-lib)
(require 'json)
(require 'org-element)
(require 'subr-x)
(require 'tabulated-list)
(require 'thingatpt)
(require 'url)
(require 'url-http)
(require 'url-parse)

(defgroup linkhole nil
  "Client for linkhole link queue."
  :group 'convenience
  :prefix "linkhole-")

(defcustom linkhole-server-url "http://127.0.0.1:8787"
  "Base URL of the server, without trailing slash."
  :type 'string)

(defcustom linkhole-token nil
  "Bearer token, used when `auth-source' has no entry for the server host."
  :type '(choice (const nil) string))

(defvar url-http-end-of-headers)
(defvar url-http-response-status)

;;;; HTTP

(defun linkhole--token ()
  "Return the bearer token or signal a `user-error'."
  (let* ((host (url-host (url-generic-parse-url linkhole-server-url)))
         (found (car (auth-source-search :host host :max 1)))
         (secret (and found (plist-get found :secret))))
    (when (functionp secret) (setq secret (funcall secret)))
    (or (and (stringp secret) (not (string-empty-p secret)) secret)
        linkhole-token
        (user-error "No linkhole token: set `linkhole-token' or add an auth-source entry for %s" host))))

(defun linkhole--request (method path data callback)
  "Send METHOD to PATH with alist DATA as JSON; call CALLBACK with (CODE BODY).
BODY is the parsed JSON (alists, nil for null/absent) or nil.  CODE is nil on
a transport error, in which case BODY is the error description."
  (let ((url-request-method method)
        (url-request-extra-headers
         `(("Authorization" . ,(concat "Bearer " (linkhole--token)))
           ("Content-Type" . "application/json")))
        (url-request-data (and data (json-serialize data)))
        (url-show-status nil))
    (url-retrieve
     (concat (string-remove-suffix "/" linkhole-server-url) path)
     (lambda (status)
       (let ((buf (current-buffer)))
         (unwind-protect
             (if (not (integerp url-http-response-status))
                 (funcall callback nil (plist-get status :error))
               ;; 4xx/5xx set :error too, but the response is still usable.
               (let ((code url-http-response-status)
                     body)
                 (goto-char (1+ url-http-end-of-headers))
                 (unless (eobp)
                   (setq body (ignore-errors
                                (json-parse-buffer :object-type 'alist
                                                   :null-object nil
                                                   :false-object nil))))
                 (funcall callback code body)))
           (kill-buffer buf))))
     nil t)))

(defun linkhole--error-message (code body)
  "Describe a failed response for the user."
  (cond ((null code) (format "request failed: %S" body))
        ((= code 401) "unauthorized (check token)")
        (t (format "HTTP %d%s" code
                   (if-let* ((e (and (listp body) (alist-get 'error body))))
                       (concat ": " e) "")))))

;;;; URL extraction

(defconst linkhole--url-regexp
  "https?://[^] \t\n\r<>\"[]+"
  "Regexp matching a bare URL (stops at `]' so org links work).")

(defun linkhole--clean-url (url)
  "Strip trailing punctuation and unbalanced `)' from URL."
  (let ((case-fold-search nil))
    (while (or (string-match-p "[.,;:!?'\"]\\'" url)
               (and (string-suffix-p ")" url)
                    (> (cl-count ?\) url) (cl-count ?\( url))))
      (setq url (substring url 0 -1))))
  url)

(defun linkhole--extract-urls (string)
  "Return the distinct http(s) URLs in STRING, in order of appearance."
  (let ((pos 0) urls)
    (while (string-match linkhole--url-regexp string pos)
      (setq pos (match-end 0))
      (let ((u (linkhole--clean-url (match-string 0 string))))
        (when (string-match-p "\\`https?://[^/]+" u)
          (cl-pushnew u urls :test #'equal))))
    (nreverse urls)))

(defun linkhole--url-at-point ()
  "Return the URL at point (org link or plain), or nil."
  (or (when (derived-mode-p 'org-mode)
        (let ((ctx (org-element-context)))
          (when (eq (org-element-type ctx) 'link)
            (let ((raw (org-element-property :raw-link ctx)))
              (car (linkhole--extract-urls (or raw "")))))))
      (let ((u (thing-at-point 'url t)))
        (and u (car (linkhole--extract-urls u))))))

;;;; Adding

(defun linkhole--add (url note callback)
  "POST URL (with optional NOTE).
CALLBACK gets `added', `duplicate' or an error string."
  (linkhole--request
   "POST" "/api/items"
   `((url . ,url) ,@(and note (not (string-empty-p note)) `((note . ,note))))
   (lambda (code body)
     (funcall callback
              (cond ((eql code 201) 'added)
                    ((eql code 200) 'duplicate)
                    (t (linkhole--error-message code body)))))))

(defun linkhole--report-single (url result)
  (pcase result
    ('added (message "linkhole: added %s" url))
    ('duplicate (message "linkhole: already queued: %s" url))
    (err (message "linkhole: failed to add %s: %s" url err))))

;;;###autoload
(defun linkhole-add (url &optional note)
  "Add URL to the queue, with optional NOTE.
Interactively, prompt for both; the URL defaults to the one at point or the
latest kill."
  (interactive
   (let* ((default (or (ignore-errors (linkhole--url-at-point))
                       (car (linkhole--extract-urls
                             (or (ignore-errors (current-kill 0 t)) "")))))
          (url (read-string (format-prompt "URL" default) nil nil default))
          (note (read-string "Note (optional): ")))
     (list url note)))
  (unless (linkhole--extract-urls url)
    (user-error "Not an http(s) URL: %s" url))
  (linkhole--add url note
                       (lambda (r) (linkhole--report-single url r))))

;;;###autoload
(defun linkhole-add-at-point ()
  "Add the org link or URL at point."
  (interactive)
  (let ((url (or (linkhole--url-at-point)
                 (user-error "No URL at point"))))
    (linkhole--add url nil
                         (lambda (r) (linkhole--report-single url r)))))

;;;###autoload
(defun linkhole-add-region (beg end)
  "Add every URL (including org links) between BEG and END, in order."
  (interactive "r")
  (let ((urls (linkhole--extract-urls (buffer-substring-no-properties beg end)))
        (added 0) (dups 0) (errors nil))
    (when (null urls) (user-error "No URLs in region"))
    ;; Sequential, so the last URL in the text ends up newest in the queue.
    (cl-labels
        ((next ()
           (if (null urls)
               (message "linkhole: %d added, %d already queued%s"
                        added dups
                        (if errors
                            (format ", %d failed (%s)" (length errors) (cdar errors))
                          ""))
             (let ((u (pop urls)))
               (linkhole--add
                u nil
                (lambda (r)
                  (pcase r
                    ('added (cl-incf added))
                    ('duplicate (cl-incf dups))
                    (err (push (cons u err) errors)))
                  (next)))))))
      (next))))

;;;; List buffer

(defconst linkhole--filters '("unread" "read" "uninteresting" "all"))

(defvar-local linkhole--filter "unread")

(defvar-keymap linkhole-list-mode-map
  :parent tabulated-list-mode-map
  "RET" #'linkhole-list-browse
  "r" #'linkhole-list-mark-read
  "x" #'linkhole-list-mark-uninteresting
  "u" #'linkhole-list-mark-unread
  "f" #'linkhole-list-cycle-filter
  "g" #'linkhole-list-refresh
  "d" #'linkhole-list-delete)

(define-derived-mode linkhole-list-mode tabulated-list-mode "Linkhole"
  "Major mode for browsing linkhole queue."
  (setq tabulated-list-format
        [("State" 13 t) ("Added" 16 t) ("Title" 50 t) ("URL" 0 t)])
  (setq tabulated-list-padding 1)
  (tabulated-list-init-header))

(defun linkhole--format-time (ms)
  (format-time-string "%Y-%m-%d %H:%M" (/ ms 1000)))

(defun linkhole--entry (item)
  "Convert ITEM (alist) to a `tabulated-list-entries' element."
  (let-alist item
    (list .id (vector .state
                      (linkhole--format-time .created_at)
                      (or .title "—")
                      .url))))

(defun linkhole--update-mode-name ()
  (setq mode-name (format "Linkhole[%s]" linkhole--filter))
  (force-mode-line-update))

(defun linkhole-list-refresh ()
  "Reload the list from the server."
  (interactive nil linkhole-list-mode)
  (let ((buf (current-buffer))
        (filter linkhole--filter))
    (linkhole--update-mode-name)
    (linkhole--request
     "GET" (format "/api/items?state=%s" filter) nil
     (lambda (code body)
       (cond
        ((not (eql code 200))
         (message "linkhole: %s" (linkhole--error-message code body)))
        ((buffer-live-p buf)
         (with-current-buffer buf
           (setq tabulated-list-entries
                 (mapcar #'linkhole--entry (append body nil)))
           (tabulated-list-print t))))))))

;;;###autoload
(defun linkhole-list ()
  "Show the queue in the `*linkhole*' buffer."
  (interactive)
  (with-current-buffer (get-buffer-create "*linkhole*")
    (unless (derived-mode-p 'linkhole-list-mode)
      (linkhole-list-mode))
    (pop-to-buffer (current-buffer))
    (linkhole-list-refresh)))

(defun linkhole-list-cycle-filter ()
  "Cycle the state filter: unread, read, uninteresting, all."
  (interactive nil linkhole-list-mode)
  (setq linkhole--filter
        (or (cadr (member linkhole--filter linkhole--filters))
            (car linkhole--filters)))
  (linkhole-list-refresh))

(defun linkhole--current-url ()
  (aref (tabulated-list-get-entry) 3))

(defun linkhole-list-browse ()
  "Open the URL at point in the browser."
  (interactive nil linkhole-list-mode)
  (when (tabulated-list-get-entry)
    (browse-url (linkhole--current-url))))

(defun linkhole--set-state (state)
  (let ((id (or (tabulated-list-get-id) (user-error "No item at point")))
        (buf (current-buffer)))
    (linkhole--request
     "PATCH" (format "/api/items/%d" id) `((state . ,state))
     (lambda (code body)
       (if (eql code 200)
           (when (buffer-live-p buf)
             (with-current-buffer buf (linkhole-list-refresh)))
         (message "linkhole: %s" (linkhole--error-message code body)))))))

(defun linkhole-list-mark-read ()
  (interactive nil linkhole-list-mode)
  (linkhole--set-state "read"))

(defun linkhole-list-mark-uninteresting ()
  (interactive nil linkhole-list-mode)
  (linkhole--set-state "uninteresting"))

(defun linkhole-list-mark-unread ()
  (interactive nil linkhole-list-mode)
  (linkhole--set-state "unread"))

(defun linkhole-list-delete ()
  "Delete the item at point after confirmation."
  (interactive nil linkhole-list-mode)
  (let ((id (or (tabulated-list-get-id) (user-error "No item at point")))
        (buf (current-buffer)))
    (when (y-or-n-p (format "Delete %s? " (linkhole--current-url)))
      (linkhole--request
       "DELETE" (format "/api/items/%d" id) nil
       (lambda (code body)
         (if (eql code 204)
             (when (buffer-live-p buf)
               (with-current-buffer buf (linkhole-list-refresh)))
           (message "linkhole: %s" (linkhole--error-message code body))))))))

(provide 'linkhole)
;;; linkhole.el ends here
