// Abrir o input
const input = document.querySelector('.search-input');
const options = document.querySelector('.dropdown-options');

input.addEventListener('click', function () {
  options.style.display = options.style.display === 'none' ? 'block' : 'none';

  if (options.style.display === 'block') {
    input.style.borderRadius = '24px 24px 0 0';
  } else {
    input.style.borderRadius = '24px';
  }
});

options.addEventListener('click', function (event) {
  if (event.target.classList.contains('dropdown-option')) {
    input.value = event.target.textContent;
    options.style.display = 'none';
    input.style.borderRadius = '24px';
  }
});

document.addEventListener('click', function (event) {
  if (!event.target.closest('.search-container')) {
    options.style.display = 'none';
    input.style.borderRadius = '24px';
  }
});

// Pegar o valor do input
let inputValue = '';

input.addEventListener("keydown", function (event) {
  if (event.key == 'Enter') {
    inputValue = event.target.value;
    const query = `search?query=${inputValue}`;
    urlQuery(query);
    input.value = '';
  }
});

// Jogar query na url
function urlQuery(url) {
  // history.pushState(null, "", '');
  history.pushState(null, "", url);
}
