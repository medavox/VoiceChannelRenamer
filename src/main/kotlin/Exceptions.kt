import okio.IOException

class ProfileNotFoundException(searchTerm:String) :Exception("no public Steam profile found matching '$searchTerm'")
class PrivateOwnedGamesException(user:String): Exception("User **$user** has set their list of games to private")
class PrivateFriendsException(user:String): Exception("User **$user** has set their friends list to private")
class SteamApiOrNetworkException() :IOException("Failed to get any data from Steam. The Steam API is probably just being janky; try again?")