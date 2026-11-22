{{- define "cdl.labels" -}}
app.kubernetes.io/part-of: cafedelyon
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "cdl.image" -}}
{{ required "image.tag 를 지정하세요 (--set image.tag=...)" .root.Values.image.tag | printf "%s/%s/%s:%s" .root.Values.image.registry .root.Values.image.repository .name }}
{{- end -}}
