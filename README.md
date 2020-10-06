#  Quick Compose

An easy networking library over [retrofit](https://github.com/square/retrofit) and [Jetpack](https://developer.android.com/jetpack)


##  Download

**Step 1.** Add the JitPack repository to your build file

```css
	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
```
**Step 2.** Add the dependency
```css
	dependencies {
	        implementation 'com.github.perfovipin:quickcompose:Tag'
	}
```

## Jetpack Architecture
consider the following diagram, which shows how all the modules should interact with one another after designing the app

![](https://developer.android.com/topic/libraries/architecture/images/final-architecture.png)

##  Data Architectures

The Paging Library supports the following data architectures:

-   Served only from a backend server.
-   Stored only in an on-device database.
-   A combination of the other sources, using the on-device database as a cache.
![](https://i.ibb.co/XWCYCh5/image.png)

###  Network only

To display data from a backend server, use the synchronous version of the [Retrofit API](http://square.github.io/retrofit/) to load information into [your own custom `DataSource` object](https://developer.android.com/topic/libraries/architecture/paging/data#custom-data-source).
```kotlin
fun getApiReponse() =  
    object : NetworkOnlyResource<Any>() {  
        override fun saveCallResult(item: Any?) {  
             //Save data.
        }  
  
        override fun createCall(): LiveData<ApiResponse<Any>> {  
            return mRestAPIEntity.apiResponse()  
        }  
    }.asLiveData
```
### Database only
Set up your [`RecyclerView`](https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView) to observe local storage, preferably using the [Room persistence library](https://developer.android.com/topic/libraries/architecture/room). That way, whenever data is inserted or modified in your app's database, these changes are automatically reflected in the `RecyclerView` that's displaying this data.


### Network and database
After you've started observing the database, you can listen for when the database is out of data by using [`PagedList.BoundaryCallback`](https://developer.android.com/reference/androidx/paging/PagedList.BoundaryCallback). You can then fetch more items from your network and insert them into the database. If your UI is observing the database, that's all you need to do.
```kotlin
// Informs Dagger that this class should be constructed only once.  
@Singleton  
class UserRepository @Inject constructor(  
    private val webservice: Webservice,  
 private val userDao: UserDao  
) {  
    fun getUser(userId: String): LiveData<User> {  
        return object : NetworkBoundResource<User, User>() {  
            override fun saveCallResult(item: User) {  
                userDao.save(item)  
            }  
  
            override fun shouldFetch(data: User?): Boolean {  
                return rateLimiter.canFetch(userId) && (data == null || !isFresh(data))  
            }  
  
            override fun loadFromDb(): LiveData<User> {  
                return userDao.load(userId)  
            }  
  
            override fun createCall(): LiveData<ApiResponse<User>> {  
                return webservice.getUser(userId)  
            }  
        }.asLiveData()  
    }  
}
```
## Data to UI
Show data to UI by observe the View life cycle.
```kotlin
val mViewModel = // to view model
private fun getInviteData() {  
    mViewModel.getApiResponse().observe(viewLifecycleOwner, Observer { response ->  
  when (response.status) {  
			 NetworkStatus.LOADING -> {  
                //On Loading
			  }
            NetworkStatus.SUCCESS -> {  
		            //On success
                  }  
            NetworkStatus.ERROR -> {  
	               //On error
                }  
        }  
    })  
}
```
## License
```
Copyright 2020 Quick Compose Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
