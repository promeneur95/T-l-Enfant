# Tél Enfant

Appli Android qui bride un téléphone (testée pour Samsung Galaxy A16) : l'enfant ne peut **appeler, recevoir des appels et échanger des SMS qu'avec une liste de numéros définie par le parent**. Les numéros d'urgence (112, 15, 17, 18…) restent toujours joignables.

## Fonctionnement

- L'appli devient **propriétaire de l'appareil** (Device Owner) grâce au QR code d'installation.
- Elle remplace l'écran d'accueil, le téléphone et la messagerie, et enferme le téléphone en **mode kiosque**.
- Appels et SMS venant de numéros inconnus sont **rejetés silencieusement** (compteur visible dans le menu parent).
- Après un appel au 112 (ou autre urgence), tous les appels entrants sont acceptés pendant 1 h pour que les secours puissent rappeler.
- Verrous : mode sans échec, réinitialisation depuis les Réglages, débogage USB, transfert de fichiers USB, ajout d'utilisateur ou de compte, désinstallation.
- La réinitialisation par le **mode recovery** (boutons physiques) reste possible : c'est la porte de sortie en cas de code oublié.

## Menu parent

Sur l'écran d'accueil, **appuyer 5 fois rapidement sur l'heure**, puis saisir le code admin (créé au premier lancement).

- Ajouter / supprimer des contacts autorisés
- Changer le code admin
- Vérifier les mises à jour (installation automatique, sans confirmation)
- Ouvrir les Réglages pendant 10 min (Wi-Fi, volume, etc.)
- Désactiver définitivement le bridage (le téléphone redevient normal, sans réinitialisation)

## Installation sur le téléphone de l'enfant

1. Réinitialiser le téléphone aux paramètres d'usine (ou partir d'un téléphone neuf). **N'ajouter aucun compte Google ni Samsung.**
2. Sur le tout premier écran de bienvenue, **taper 6 fois au même endroit** de l'écran. Un lecteur de QR code s'ouvre (le téléphone peut d'abord demander de se connecter au Wi-Fi).
3. Scanner `QR_installation.png`.
4. Le téléphone télécharge l'appli depuis la dernière version publiée ici, la configure et termine l'installation.
5. Au premier démarrage : créer le **code admin**, puis ajouter les contacts via le menu parent.

> Le QR code pointe toujours vers la **dernière version** publiée (`releases/latest`) et vérifie la **signature** de l'APK, pas un fichier précis : il reste valable après chaque mise à jour.

## Compilation (GitHub Actions)

Chaque `push` sur `main` compile l'APK signé et publie une Release `vN` (N = numéro de build = `versionCode`).

La clé de signature est stockée **chiffrée** dans le dépôt (`app/release.jks.enc`, AES-256). Un seul secret est requis dans **Settings → Secrets and variables → Actions** : `KEYSTORE_PASSWORD` (mot de passe de la clé, qui sert aussi à la déchiffrer).

⚠️ **Conserver précieusement la clé de signature** (`TelEnfant-release.jks`). Sans elle, impossible de publier une mise à jour installable sur le téléphone : il faudrait réinitialiser et réinstaller.

Le dépôt doit être **public** pour que le téléphone puisse télécharger l'APK (installation et mises à jour). Il ne contient aucun secret.
