import en_US from "./en_US";

const messages = {
  ...en_US,
  Edit: "Bearbeiten",
  Save: "Speichern",
  Delete: "Löschen",
  Cancel: "Abbrechen",
  Revert: "Zurücksetzen",
  Search: "Suchen",
  search: "Suchen",
  add: "hinzufügen",
  yes: "Ja",
  no: "Nein",
  Yes: "Ja",
  No: "Nein",

  "results.empty.text": "Keine Ergebnisse für diese Abfrage",
  "Any Time": "Jederzeit",

  "Edit Nodes": "Nodes bearbeiten",
  "Edit Node Sources": "Node-Quellen bearbeiten",
  "add.node.source": "Neue Node-Quelle hinzufügen",
  "add.node.enhancer": "Neuen Node-Enhancer hinzufügen",
  "project.node.sources.title.short": "Quellen",
  "framework.service.NodeEnhancer.label.short.plural": "Enhancer",
  "framework.service.NodeEnhancer.explanation":
    "Node-Enhancer können die aus Node-Quellen geladenen Daten verändern.",
  "no.modifiable.sources.found": "Keine änderbaren Quellen gefunden",
  "modifiable.node.sources.will.appear.here":
    "Änderbare Node-Quellen werden hier angezeigt.",

  "project.edit.ResourceModelSource.explanation":
    "Node-Quellen für das Projekt. Quellen werden in der definierten Reihenfolge geladen; spätere Quellen überschreiben frühere Quellen. (Sie können {'${project.name}'} in Konfigurationswerten verwenden, um den Projektnamen einzusetzen.)",

  "storage.enter.path": "Pfad eingeben",
  "storage.enter.password": "Passwort eingeben",
  "storage.enter.directory.name": "Verzeichnisnamen eingeben",
  "storage.specify.name": "Namen angeben.",

  "Search Activity": "Aktivität durchsuchen",
  "Save as a Filter...": "Als Filter speichern...",
  "job.filter.save.button.title": "Als Filter speichern...",

  notifications: {
    ...en_US.notifications,
    helpText:
      "Benachrichtigungen können durch verschiedene Ereignisse während der Job-Ausführung ausgelöst werden.",
    emptyText:
      "Es sind keine Benachrichtigungen definiert. Klicken Sie unten auf ein Ereignis, um eine Benachrichtigung für diesen Auslöser hinzuzufügen.",
    addButton: "Benachrichtigung hinzufügen",
    triggerLabel: "Auslöser",
    selectTrigger: "Auslöser auswählen",
    typeLabel: "Benachrichtigungstyp",
    selectNotification: "Benachrichtigung auswählen",
  },

  message_webhookPageTitle: "Webhooks",
  message_webhookListTitle: "Webhooks",
  message_webhookDetailTitle: "Webhook-Details",
  message_addWebhookBtn: "Hinzufügen",
  message_webhookEnabledLabel: "Aktiviert",
  message_webhookPluginCfgTitle: "Plugin-Konfiguration",
  message_webhookSaveBtn: "Speichern",
  message_webhookCreateBtn: "Webhook erstellen",
  message_webhookDeleteBtn: "Löschen",
  message_webhookPostUrlLabel: "POST-URL",
  message_webhookPostUrlHelp:
    "Wenn eine HTTP-POST-Anfrage an diese URL empfangen wird, erhält das unten ausgewählte Webhook-Plugin die Daten.",
  message_webhookPostUrlPlaceholder:
    "Die URL wird erzeugt, nachdem der Webhook erstellt wurde",
  message_webhookNameLabel: "Name",
  message_webhookUserLabel: "Benutzer",
  message_webhookRolesLabel: "Rollen",
  message_webhookAuthLabel: "HTTP-Autorisierungszeichenfolge",
  message_webhookGenerateSecurityLabel: "Authorization-Header verwenden",
  message_webhookPluginLabel: "Webhook-Plugin auswählen",
  message_webhookFilterListPlaceholder: "Webhooks filtern",
  message_webhookTabGeneral: "Allgemein",
  message_webhookTabHandlerConfiguration: "Handler-Konfiguration",
  message_webhookButtonRegenerate: "Neu generieren",
  message_webhookNewHookName: "Neuer Webhook",

  resourcesEditor: {
    ...en_US.resourcesEditor,
    "Dispatch to Nodes": "Auf Nodes ausführen",
    Nodes: "Nodes",
  },

  Workflow: {
    ...en_US.Workflow,
    addStep: "Schritt hinzufügen",
    logFilters: "Log-Filter",
    addLogFilter: "Log-Filter hinzufügen",
    clickToEdit: "Zum Bearbeiten klicken",
    edit: "Bearbeiten",
    deleteThisStep: "Diesen Schritt löschen",
    dragToReorder: "Zum Neuordnen ziehen",
    clickOnStepType: "Klicken Sie auf einen Schritttyp, um ihn hinzuzufügen",
    editStep: "Schritt bearbeiten",
    stepLabel: "Schrittbezeichnung",
    noSteps: "Keine Workflow-Schritte",
    addErrorHandler: "Fehlerbehandlung hinzufügen",
    errorHandler: "Fehlerbehandlung",
    editErrorHandler: "Fehlerbehandlung bearbeiten",
  },

  period: {
    ...en_US.period,
    label: {
      ...(en_US.period?.label || {}),
      All: "jederzeit",
      Hour: "in der letzten Stunde",
      Day: "am letzten Tag",
      Week: "in der letzten Woche",
      Month: "im letzten Monat",
    },
  },
};

export default messages;
